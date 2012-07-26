/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cluster;

import com.hazelcast.impl.*;
import com.hazelcast.impl.base.Call;
import com.hazelcast.impl.base.PacketProcessor;
import com.hazelcast.impl.base.ScheduledAction;
import com.hazelcast.impl.spi.*;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.*;
import com.hazelcast.security.Credentials;
import com.hazelcast.util.Clock;
import com.hazelcast.util.Prioritized;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import static com.hazelcast.impl.ClusterOperation.REMOTE_CALL;
import static com.hazelcast.nio.IOUtil.toData;
import static com.hazelcast.nio.IOUtil.toObject;

public final class ClusterManager extends BaseManager implements ConnectionListener {

    public static final String SERVICE_NAME = "hz:ClusterService";

    private final long waitMillisBeforeJoin;

    private final long maxWaitSecondsBeforeJoin;

    private final long maxNoHeartbeatMillis;

    private final long heartbeatIntervalMillis;

    private final boolean icmpEnabled;

    private final int icmpTtl;

    private final int icmpTimeout;

    private final Lock lock = new ReentrantLock();

    private final Set<ScheduledAction> setScheduledActions = new LinkedHashSet<ScheduledAction>(1000);

    private final Set<MemberInfo> setJoins = new LinkedHashSet<MemberInfo>(100);
//    private final Set<MemberInfo> setJoins = new CopyOnWriteArraySet<MemberInfo>();

    private final AtomicReference<Map<Address, MemberImpl>> membersRef = new AtomicReference<Map<Address, MemberImpl>>();

    private final AtomicInteger dataMemberCount = new AtomicInteger();

    private boolean joinInProgress = false;

    private long timeToStartJoin = 0;

    private long firstJoinRequest = 0;

//    private final List<MemberImpl> lsMembersBefore = new ArrayList<MemberImpl>();

    private long lastHeartbeat = 0;

    final ILogger securityLogger;

    private final Data heartbeatOperationData ;

    public ClusterManager(final Node node) {
        super(node);
        securityLogger = node.loggingService.getLogger("com.hazelcast.security");
        waitMillisBeforeJoin = node.groupProperties.WAIT_SECONDS_BEFORE_JOIN.getInteger() * 1000L;
        maxWaitSecondsBeforeJoin = node.groupProperties.MAX_WAIT_SECONDS_BEFORE_JOIN.getInteger();
        maxNoHeartbeatMillis = node.groupProperties.MAX_NO_HEARTBEAT_SECONDS.getInteger() * 1000L;
        heartbeatIntervalMillis = node.groupProperties.HEARTBEAT_INTERVAL_SECONDS.getInteger() * 1000L;
        icmpEnabled = node.groupProperties.ICMP_ENABLED.getBoolean();
        icmpTtl = node.groupProperties.ICMP_TTL.getInteger();
        icmpTimeout = node.groupProperties.ICMP_TIMEOUT.getInteger();
        heartbeatOperationData = toData(new HeartbeatOperation()) ;
        node.clusterService.registerPeriodicRunnable(new SplitBrainHandler(node));
        node.clusterService.registerPeriodicRunnable(new Runnable() {
            public void run() {
                long now = Clock.currentTimeMillis();
                if (now - lastHeartbeat >= heartbeatIntervalMillis) {
                    heartBeater();
                    lastHeartbeat = now;
                }
            }
        });
        node.clusterService.registerPeriodicRunnable(new Runnable() {
            public void run() {
                checkScheduledActions();
            }
        });
        node.connectionManager.addConnectionListener(this);
        registerPacketProcessor(ClusterOperation.RESPONSE, new PacketProcessor() {
            public void process(Packet packet) {
                handleResponse(packet);
            }
        });
        registerPacketProcessor(ClusterOperation.HEARTBEAT, new PacketProcessor() {
            public void process(Packet packet) {
                releasePacket(packet);
            }
        });
        registerPacketProcessor(ClusterOperation.LOG, new PacketProcessor() {
            public void process(Packet packet) {
                logger.log(Level.parse(packet.name), toObject(packet.getValueData()).toString());
                releasePacket(packet);
            }
        });
        registerPacketProcessor(ClusterOperation.JOIN_CHECK, new PacketProcessor() {
            public void process(Packet packet) {
                Connection conn = packet.conn;
                Request request = Request.copyFromPacket(packet);
                JoinInfo joinInfo = (JoinInfo) toObject(request.value);
                request.clearForResponse();
                if (joinInfo != null && node.joined() && node.isActive()) {
                    try {
                        node.validateJoinRequest(joinInfo);
                        request.response = toData(node.createJoinInfo());
                    } catch (Exception e) {
                        request.response = toData(e);
                    }
                }
                returnResponse(request, conn);
                releasePacket(packet);
            }
        });
        registerPacketProcessor(ClusterOperation.REMOTELY_PROCESS_AND_RESPOND,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Data data = packet.getValueData();
                        RemotelyProcessable rp = (RemotelyProcessable) toObject(data);
                        rp.setConnection(packet.conn);
                        rp.setNode(node);
                        rp.process();
                        sendResponse(packet);
                    }
                });
        registerPacketProcessor(ClusterOperation.REMOTELY_PROCESS,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Data data = packet.getValueData();
                        RemotelyProcessable rp = (RemotelyProcessable) toObject(data);
                        rp.setConnection(packet.conn);
                        rp.setNode(node);
                        rp.process();
                        releasePacket(packet);
                    }
                });
        registerPacketProcessor(ClusterOperation.REMOTELY_CALLABLE_BOOLEAN,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Boolean result;
                        AbstractRemotelyCallable<Boolean> callable = null;
                        try {
                            Data data = packet.getValueData();
                            callable = (AbstractRemotelyCallable<Boolean>) toObject(data);
                            System.out.println("callable = " + callable);
                            callable.setConnection(packet.conn);
                            callable.setNode(node);
                            result = callable.call();
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Error processing " + callable, e);
                            result = Boolean.FALSE;
                        }
                        if (result == Boolean.TRUE) {
                            sendResponse(packet);
                        } else {
                            sendResponseFailure(packet);
                        }
                    }
                });
        registerPacketProcessor(ClusterOperation.REMOTELY_CALLABLE_OBJECT,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Object result;
                        AbstractRemotelyCallable<Boolean> callable = null;
                        try {
                            Data data = packet.getValueData();
                            callable = (AbstractRemotelyCallable) toObject(data);
                            callable.setConnection(packet.conn);
                            callable.setNode(node);
                            result = callable.call();
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Error processing " + callable, e);
                            result = null;
                        }
                        if (result != null) {
                            Data value;
                            if (result instanceof Data) {
                                value = (Data) result;
                            } else {
                                value = toData(result);
                            }
                            packet.setValue(value);
                        }
                        sendResponse(packet);
                    }
                });

        node.nodeService.registerService(SERVICE_NAME, this);
    }

    public boolean shouldTryMerge() {
        lock.lock();
        try {
            return !joinInProgress && setJoins.size() == 0;
        } finally {
            lock.unlock();
        }

    }

//    public void appendState(StringBuffer sbState) {
//        sbState.append("\nClusterManager {");
//        for (ScheduledAction sa : setScheduledActions) {
//            sbState.append("\n\t" + sa + ", from:" + sa.getRequest().caller);
//        }
//        sbState.append("\n}");
//    }

    public JoinInfo checkJoin(Connection conn) {
        return new JoinCall(conn).checkJoin();
    }

    class JoinCall extends ConnectionAwareOp {
        JoinCall(Connection target) {
            super(target);
        }

        JoinInfo checkJoin() {
            setLocal(ClusterOperation.JOIN_CHECK, "join", null, node.createJoinInfo(), 0, 0);
            doOp();
            return (JoinInfo) getResultAsObject();
        }
    }

    private void logMissingConnection(Address address) {
        String msg = thisMember + " has no connection to " + address;
        logAtMaster(Level.WARNING, msg);
        logger.log(Level.WARNING, msg);
    }

    private void logAtMaster(Level level, String msg) {
        Address master = getMasterAddress();
        if (!isMaster() && master != null) {
            Connection connMaster = node.connectionManager.getOrConnect(getMasterAddress());
            if (connMaster != null) {
                Packet packet = obtainPacket(level.toString(), null, toData(msg), ClusterOperation.LOG, 0);
                sendOrReleasePacket(packet, connMaster);
            }
        } else {
            logger.log(level, msg);
        }
    }

    public final void heartBeater() {
        if (!node.joined() || !node.isActive()) return;
        long now = Clock.currentTimeMillis();
        final Collection<MemberImpl> members = getMembers();
        if (isMaster()) {
            List<Address> lsDeadAddresses = null;
            for (MemberImpl memberImpl : members) {
                final Address address = memberImpl.getAddress();
                if (!thisAddress.equals(address)) {
                    try {
                        Connection conn = node.connectionManager.getOrConnect(address);
                        if (conn != null && conn.live()) {
                            if ((now - memberImpl.getLastRead()) >= (maxNoHeartbeatMillis)) {
                                if (lsDeadAddresses == null) {
                                    lsDeadAddresses = new ArrayList<Address>();
                                }
                                logger.log(Level.WARNING, "Added " + address + " to list of dead addresses because of timeout since last read");
                                lsDeadAddresses.add(address);
                            } else if ((now - memberImpl.getLastRead()) >= 5000 && (now - memberImpl.getLastPing()) >= 5000) {
                                ping(memberImpl);
                            }
                            if ((now - memberImpl.getLastWrite()) > 500) {
                                sendHeartbeat(address);
                            }
                        } else if (conn == null && (now - memberImpl.getLastRead()) > 5000) {
                            logMissingConnection(address);
                            memberImpl.didRead();
                        }
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            }
            if (lsDeadAddresses != null) {
                for (Address address : lsDeadAddresses) {
                    logger.log(Level.FINEST, "No heartbeat should remove " + address);
                    doRemoveAddress(address);
                }
            }
        } else {
            // send heartbeat to master
            Address masterAddress = getMasterAddress();
            if (masterAddress != null) {
                node.connectionManager.getOrConnect(masterAddress);
                MemberImpl masterMember = getMember(masterAddress);
                boolean removed = false;
                if (masterMember != null) {
                    if ((now - masterMember.getLastRead()) >= (maxNoHeartbeatMillis)) {
                        logger.log(Level.WARNING, "Master node has timed out its heartbeat and will be removed");
                        doRemoveAddress(masterAddress);
                        removed = true;
                    } else if ((now - masterMember.getLastRead()) >= 5000 && (now - masterMember.getLastPing()) >= 5000) {
                        ping(masterMember);
                    }
                }
                if (!removed) {
                    sendHeartbeat(masterAddress);
                }
            }
            for (MemberImpl member : members) {
                if (!member.localMember()) {
                    Address address = member.getAddress();
                    Connection conn = node.connectionManager.getOrConnect(address);
                    if (conn != null) {
                        sendHeartbeat(address);
                    } else {
                        logger.log(Level.FINEST, "could not connect to " + address + " to send heartbeat");
                    }
                }
            }
        }
    }

    private void ping(final MemberImpl memberImpl) {
        memberImpl.didPing();
        if (!icmpEnabled) return;
        node.executorManager.executeNow(new Runnable() {
            public void run() {
                try {
                    final Address address = memberImpl.getAddress();
                    logger.log(Level.WARNING, thisAddress + " will ping " + address);
                    for (int i = 0; i < 5; i++) {
                        try {
                            if (address.getInetAddress().isReachable(null, icmpTtl, icmpTimeout)) {
                                logger.log(Level.INFO, thisAddress + " pings successfully. Target: " + address);
                                return;
                            }
                        } catch (ConnectException ignored) {
                            // no route to host
                            // means we cannot connect anymore
                        }
                    }
                    logger.log(Level.WARNING, thisAddress + " couldn't ping " + address);
                    // not reachable.
//                    enqueueAndReturn(new Processable() {
//                        public void process() {
                            doRemoveAddress(address);
//                        }
//                    });
                } catch (Throwable ignored) {
                }
            }
        });
    }

    void sendHeartbeat(Address target) {
        if (target == null) return;
//        Packet packet = obtainPacket("heartbeat", null, null, ClusterOperation.HEARTBEAT, 0);
//        sendOrReleasePacket(packet, conn);
        Packet packet = new Packet();
        packet.operation = REMOTE_CALL;
        packet.blockId = -1;
        packet.name = SERVICE_NAME;
        packet.longValue = 1;
        packet.setValue(heartbeatOperationData);
        sendOrReleasePacket(packet, target);
    }

    public void handleMaster(Master master) {
        if (!node.joined() && !thisAddress.equals(master.address)) {
            node.setMasterAddress(master.address);
            Connection connMaster = node.connectionManager.getOrConnect(master.address);
            if (connMaster != null) {
                sendJoinRequest(master.address, true);
            }
        }
    }

    public void handleAddRemoveConnection(final AddOrRemoveConnection connection) {
        if (connection.add) { // Just connect to the new address if not connected already.
            if (!connection.address.equals(thisAddress)) {
                node.connectionManager.getOrConnect(connection.address);
            }
        } else { // Remove dead member
            if (connection.address != null) {
                logger.log(Level.FINEST, "Disconnected from " + connection.address + "... will be removed!");
                doRemoveAddress(connection.address);
            }
        } // end of REMOVE CONNECTION
    }

    void doRemoveAddress(Address deadAddress) {
        doRemoveAddress(deadAddress, true);
    }

    void doRemoveAddress(Address deadAddress, boolean destroyConnection) {
//        checkServiceThread();
        logger.log(Level.INFO, "Removing Address " + deadAddress);
        if (!node.joined()) {
            node.failedConnection(deadAddress);
            return;
        }
        if (deadAddress.equals(thisAddress)) {
            return;
        }
        lock.lock();
        try {
            if (deadAddress.equals(getMasterAddress())) {
                if (node.joined()) {
                    MemberImpl newMaster = getNextMemberAfter(deadAddress, false, 1);
                    if (newMaster != null) {
                        node.setMasterAddress(newMaster.getAddress());
                    } else {
                        node.setMasterAddress(null);
                    }
                } else {
                    node.setMasterAddress(null);
                }
                logger.log(Level.FINEST, "Now Master " + node.getMasterAddress());
            }
            if (isMaster()) {
                setJoins.remove(new MemberInfo(deadAddress));
            }
            final Connection conn = node.connectionManager.getConnection(deadAddress);
            if (destroyConnection && conn != null) {
                node.connectionManager.destroyConnection(conn);
            }
            MemberImpl deadMember = getMember(deadAddress);
            if (deadMember != null) {
                //            lsMembersBefore.clear();
                //            for (MemberImpl memberBefore : lsMembers) {
                //                lsMembersBefore.add(memberBefore);
                //            }
                removeMember(deadMember);
                //            node.getClusterImpl().setMembers(lsMembers);
                node.getClusterImpl().setMembers(getMembers());
                node.partitionManager.syncForDead(deadMember);
                node.concurrentMapManager.syncForDead(deadMember);
                node.blockingQueueManager.syncForDead(deadMember);
                node.listenerManager.syncForDead(deadAddress);
                node.topicManager.syncForDead(deadAddress);
                disconnectExistingCalls(deadAddress);
                if (isMaster()) {
                    logger.log(Level.FINEST, deadAddress + " is dead. Sending remove to all other members.");
                    sendRemoveMemberToOthers(deadAddress);
                }
                logger.log(Level.INFO, this.toString());
            }
        } finally {
            lock.unlock();
        }

    }

    private void sendRemoveMemberToOthers(final Address deadAddress) {
        for (MemberImpl member : getMembers()) {
            Address address = member.getAddress();
            if (!thisAddress.equals(address) && !address.equals(deadAddress)) {
//                sendProcessableTo(new MemberRemover(deadAddress), address);
                node.nodeService.createSingleInvocation(SERVICE_NAME, new MemberRemover(deadAddress), -1)
                        .setTarget(address).setTryCount(1).build().invoke();
            }
        }
    }

    public void disconnectExistingCalls(Address deadAddress) {
        Object[] calls = mapCalls.values().toArray();
        for (Object call : calls) {
            ((Call) call).onDisconnect(deadAddress);
        }
    }

    void handleJoinRequest(JoinRequest joinRequest) {
        lock.lock();
        try {
            final long now = Clock.currentTimeMillis();
            String msg = "Handling join from " + joinRequest.address + ", inProgress: " + joinInProgress
                         + (timeToStartJoin > 0 ? ", timeToStart: " + (timeToStartJoin - now) : "");
            logger.log(Level.FINEST, msg);
            final MemberImpl member = getMember(joinRequest.address);
            final Connection conn = null; //joinRequest.getConnection();
            if (member != null) {
                if (joinRequest.getUuid().equals(member.getUuid())) {
                    String message = "Ignoring join request, member already exists.. => " + joinRequest;
                    logger.log(Level.FINEST, message);
                    return;
                }
                logger.log(Level.WARNING, "New join request has been received from an existing endpoint! => " + member
                                          + " Removing old member and processing join request...");
                // If existing connection of endpoint is different from current connection
                // destroy it, otherwise keep it.
                final Connection existingConnection = node.connectionManager.getConnection(joinRequest.address);
                final boolean destroyExistingConnection = existingConnection != conn;
                doRemoveAddress(member.getAddress(), destroyExistingConnection);
            }
            boolean validateJoinRequest;
            try {
                validateJoinRequest = node.validateJoinRequest(joinRequest);
            } catch (Exception e) {
                validateJoinRequest = false;
            }
            if (validateJoinRequest) {
                if (!node.getConfig().getNetworkConfig().getJoin().getMulticastConfig().isEnabled()) {
                    if (node.isActive() && node.joined() && node.getMasterAddress() != null && !isMaster()) {
                        //                    sendProcessableTo(new Master(node.getMasterAddress()), conn);
                        sendMaster(joinRequest);
                    }
                }
                if (isMaster() && node.joined() && node.isActive()) {
                    final MemberInfo newMemberInfo = new MemberInfo(joinRequest.address, joinRequest.nodeType,
                            joinRequest.getUuid());
                    if (node.securityContext != null && !setJoins.contains(newMemberInfo)) {
                        final Credentials cr = joinRequest.getCredentials();
                        if (cr == null) {
                            securityLogger.log(Level.SEVERE, "Expecting security credentials " +
                                                             "but credentials could not be found in JoinRequest!");
                            sendAuthFail(joinRequest.address);
                            return;
                        } else {
                            try {
                                LoginContext lc = node.securityContext.createMemberLoginContext(cr);
                                lc.login();
                            } catch (LoginException e) {
                                securityLogger.log(Level.SEVERE, "Authentication has failed for " + cr.getPrincipal()
                                                                 + '@' + cr.getEndpoint() + " => (" + e.getMessage() +
                                                                 ")");
                                securityLogger.log(Level.FINEST, e.getMessage(), e);
                                sendAuthFail(joinRequest.address);
                                return;
                            }
                        }
                    }
                    if (joinRequest.to != null && !joinRequest.to.equals(thisAddress)) {
                        //                    sendProcessableTo(new Master(node.getMasterAddress()), conn);
                        sendMaster(joinRequest);
                        return;
                    }
                    if (!joinInProgress) {
                        if (firstJoinRequest != 0 && now - firstJoinRequest >= maxWaitSecondsBeforeJoin * 1000) {
                            startJoin();
                        } else {
                            if (setJoins.add(newMemberInfo)) {
                                // TODO: send master
                                //                            sendProcessableTo(new Master(node.getMasterAddress()), conn);
                                sendMaster(joinRequest);
                                if (firstJoinRequest == 0) {
                                    firstJoinRequest = now;
                                }
                                if (now - firstJoinRequest < maxWaitSecondsBeforeJoin * 1000) {
                                    timeToStartJoin = now + waitMillisBeforeJoin;
                                }
                            }
                            if (now > timeToStartJoin) {
                                startJoin();
                            }
                        }
                    }
                }
            } else {
                conn.close();
            }
        } finally {
            lock.unlock();
        }

    }

    private void sendMaster(final JoinRequest joinRequest) {
        node.nodeService.createSingleInvocation(SERVICE_NAME,
            new Master(node.getMasterAddress()), -1).setTarget(joinRequest.address)
                .setTryCount(1).build().invoke();
    }

    public static class AuthenticationFailureOperation extends AbstractOperation
            implements NoReply, NonMemberOperation, NonBlockingOperation {

        public Object call() throws Exception {
            final Node node = getOperationContext().getNodeService().getNode();
            node.executorManager.executeNow(new Runnable() {
                public void run() {
                    final ILogger logger = node.loggingService.getLogger("com.hazelcast.security");
                    logger.log(Level.SEVERE, "Authentication failed on master node! Node is going to shutdown now!");
                    node.shutdown(true, true);
                }
            });
            return null;
        }
    }

    private void sendAuthFail(Address target) {
//        sendProcessableTo(new AuthenticationFailureProcessable(), conn);
        node.nodeService.createSingleInvocation(SERVICE_NAME, new AuthenticationFailureOperation(), -1)
                .setTarget(target).setTryCount(1).build().invoke();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("\n\nMembers [");
//        sb.append(lsMembers.size());
        final Collection<MemberImpl> members = getMembers();
        sb.append(members.size());
        sb.append("] {");
//        for (MemberImpl member : lsMembers) {
        for (MemberImpl member : members) {
            sb.append("\n\t").append(member);
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private void joinReset() {
        lock.lock();
        try {
            joinInProgress = false;
            setJoins.clear();
            timeToStartJoin = Clock.currentTimeMillis() + waitMillisBeforeJoin;
            firstJoinRequest = 0;
        } finally {
            lock.unlock();
        }

    }

    public void onRestart() {
//        enqueueAndWait(new Processable() {
//            public void process() {
                joinReset();
//                lsMembers.clear();
//                mapMembers.clear();
                membersRef.set(null);
                dataMemberCount.set(0);
//            }
//        }, 5);
    }

    public boolean checkAuthorization(String groupName, String groupPassword, Address target) {
        AbstractRemotelyCallable<Boolean> authorizationCall = new AuthorizationCall(groupName, groupPassword);
        AsyncRemotelyBooleanCallable call = new NoneMemberAsyncRemotelyBooleanCallable();
        call.executeProcess(target, authorizationCall);
        return call.getResultAsBoolean();
    }

    public class NoneMemberAsyncRemotelyBooleanCallable extends AsyncRemotelyBooleanCallable {
        @Override
        protected boolean memberOnly() {
            return false;
        }
    }

    public class AsyncRemotelyBooleanCallable extends TargetAwareOp {
        AbstractRemotelyCallable<Boolean> arp = null;

        public void executeProcess(Address address, AbstractRemotelyCallable<Boolean> arp) {
            this.arp = arp;
            super.target = address;
            arp.setNode(node);
            setLocal(ClusterOperation.REMOTELY_CALLABLE_BOOLEAN, "call", null, arp, 0, -1);
            request.setBooleanRequest();
            doOp();
        }

        @Override
        public Address getTarget() {
            return target;
        }

        @Override
        public void onDisconnect(final Address dead) {
            if (dead.equals(target)) {
                removeRemoteCall(getCallId());
                setResult(Boolean.FALSE);
            }
        }

        @Override
        public void doLocalOp() {
            Boolean result;
            try {
                result = arp.call();
                setResult(result);
            } catch (Exception e) {
                logger.log(Level.FINEST, e.getMessage(), e);
            }
        }

        @Override
        public void setTarget() {
        }

        @Override
        public void redo(int redoTypeCode) {
            removeRemoteCall(getCallId());
            setResult(Boolean.FALSE);
        }

        @Override
        protected void memberDoesNotExist() {
            setResult(Boolean.FALSE);
        }

        @Override
        protected void packetNotSent() {
            setResult(Boolean.FALSE);
        }
    }

    public void finalizeJoin() {
//        List<AsyncRemotelyBooleanCallable> calls = new ArrayList<AsyncRemotelyBooleanCallable>();
//        for (MemberImpl member : getMembers()) {
//            if (!member.localMember() && !member.isLiteMember()) {
//                AsyncRemotelyBooleanCallable rrp = new AsyncRemotelyBooleanCallable();
//                rrp.executeProcess(member.getAddress(), new FinalizeJoin());
//                calls.add(rrp);
//            }
//        }
//        for (AsyncRemotelyBooleanCallable call : calls) {
//            call.getResultAsBoolean();
//        }
        List<Future> calls = new ArrayList<Future>();
        for (MemberImpl member : getMembers()) {
            if (!member.localMember() && !member.isLiteMember()) {
                Invocation inv = node.nodeService.createSingleInvocation(SERVICE_NAME, new FinalizeJoin(), -1)
                        .setTarget(member.getAddress()).setTryCount(1).build();
                calls.add(inv.invoke());
            }
        }
//        for (Future future : calls) {
//            future.get();
//        }
    }

    class JoinRunnable extends FallThroughRunnable implements Prioritized {

        final MembersUpdateCall membersUpdate;

        JoinRunnable(MembersUpdateCall membersUpdate) {
            this.membersUpdate = membersUpdate;
        }

        @Override
        public void doRun() {
            Collection<MemberInfo> lsMemberInfos = membersUpdate.getMemberInfos();
            List<Address> newMemberList = new ArrayList<Address>(lsMemberInfos.size());
            for (final MemberInfo memberInfo : lsMemberInfos) {
                newMemberList.add(memberInfo.address);
            }
//            doCall(membersUpdate, newMemberList, true);
            doCall(membersUpdate, newMemberList, false);
            systemLogService.logJoin("JoinRunnable update members done.");
            doCall(new SyncProcess(), newMemberList, false);
            systemLogService.logJoin("JoinRunnable sync done.");
            doCall(new ConnectionCheckCall(), newMemberList, false);
            systemLogService.logJoin("JoinRunnable connection check done.");
        }

        void doCall(Operation operation, List<Address> targets, boolean ignoreThis) {
            List<Future> calls = new ArrayList<Future>(targets.size());
//            for (final Address target : targets) {
//                boolean skip = ignoreThis && thisAddress.equals(target);
//                if (!skip) {
//                    AsyncRemotelyBooleanCallable call = new AsyncRemotelyBooleanCallable();
//                    call.executeProcess(target, operation);
//                    calls.add(call);
//                }
//            }
//            for (AsyncRemotelyBooleanCallable call : calls) {
//                if (!call.getResultAsBoolean(5)) {
//                    targets.remove(call.getTarget());
//                }
//            }
            for (final Address target : targets) {
                boolean skip = ignoreThis && thisAddress.equals(target);
                if (!skip) {
                    Invocation inv = node.nodeService.createSingleInvocation(SERVICE_NAME, operation, -1)
                            .setTarget(target).setTryCount(1).build();
                    calls.add(inv.invoke());
                }
            }
            for (Future future : calls) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.log(Level.WARNING, e.getMessage(), e);
                }
            }
        }
    }

    void startJoin() {
        logger.log(Level.FINEST, "Starting Join.");
        lock.lock();
        try {
            joinInProgress = true;
            //        final MembersUpdateCall membersUpdate = new MembersUpdateCall(lsMembers, node.getClusterImpl().getClusterTime());
            final MembersUpdateCall membersUpdate = new MembersUpdateCall(getMembers(),
                    node.getClusterImpl().getClusterTime());
            if (setJoins != null && setJoins.size() > 0) {
                for (MemberInfo memberJoined : setJoins) {
                    membersUpdate.addMemberInfo(memberJoined);
                }
            }
            //        membersUpdate.setNode(node);
            //        membersUpdate.call();
            node.executorManager.executeNow(new JoinRunnable(membersUpdate));
        } finally {
            lock.unlock();
        }

    }

    void updateMembers(Collection<MemberInfo> lsMemberInfos) {
//        checkServiceThread();
        logger.log(Level.FINEST, "Updating Members");
        lock.lock();
        try {
            // Copy lsMembers to lsMembersBefore
            //        lsMembersBefore.clear();
            Map<Address, MemberImpl> mapOldMembers = new HashMap<Address, MemberImpl>();
            //        for (MemberImpl member : lsMembers) {
            for (MemberImpl member : getMembers()) {
                //            lsMembersBefore.add(member);
                mapOldMembers.put(member.getAddress(), member);
            }
            //        lsMembers.clear();
            //        dataMemberCount.set(0);
            //        mapMembers.clear();
            System.out.println("lsMemberInfos = " + lsMemberInfos);
            MemberImpl[] newMembers = new MemberImpl[lsMemberInfos.size()];
            int k = 0;
            for (MemberInfo memberInfo : lsMemberInfos) {
                MemberImpl member = mapOldMembers.get(memberInfo.address);
                if (member == null) {
//                    member = addMember(memberInfo.address, memberInfo.nodeType, memberInfo.uuid);
                    member = createMember(memberInfo.address, memberInfo.nodeType, memberInfo.uuid,
                            thisAddress.getScopeId());
                }
                newMembers[k++] = member;
                member.didRead();
            }
            addMembers(newMembers);
            System.out.println("getMembers() = " + getMembers());
            System.out.println("dataMemberCount = " + dataMemberCount);
            //        if (!lsMembers.contains(thisMember)) {
            if (!getMembers().contains(thisMember)) {
                throw new RuntimeException("Member list doesn't contain local member!");
            }
            heartBeater();
            //        node.getClusterImpl().setMembers(lsMembers);
            node.getClusterImpl().setMembers(getMembers());
            node.setJoined();
            logger.log(Level.INFO, this.toString());
        } finally {
            lock.unlock();
        }

    }

    void syncForAdd() {
        lock.lock();
        try {
            node.partitionManager.syncForAdd();
            node.listenerManager.syncForAdd();
            node.topicManager.syncForAdd();
            joinReset();
        } finally {
            lock.unlock();
        }

    }

    public boolean sendJoinRequest(Address toAddress, boolean withCredentials) {
        if (toAddress == null) {
            toAddress = node.getMasterAddress();
        }
//        return sendProcessableTo(node.createJoinInfo(withCredentials), toAddress);
        JoinRequest joinRequest = node.createJoinInfo(withCredentials);
        Invocation inv = node.nodeService.createSingleInvocation(SERVICE_NAME, joinRequest, -1)
                .setTarget(toAddress).setTryCount(1).build();
        inv.invoke();
        return true;
    }

    public void registerScheduledAction(ScheduledAction scheduledAction) {
        setScheduledActions.add(scheduledAction);
    }

    public void deregisterScheduledAction(ScheduledAction scheduledAction) {
        setScheduledActions.remove(scheduledAction);
    }

    public void checkScheduledActions() {
        if (!node.joined() || !node.isActive()) return;
        if (setScheduledActions.size() > 0) {
            Iterator<ScheduledAction> it = setScheduledActions.iterator();
            while (it.hasNext()) {
                ScheduledAction sa = it.next();
                if (sa.expired() && sa.isValid()) {
                    sa.onExpire();
                    it.remove();
                } else if (!sa.isValid()) {
                    it.remove();
                }
            }
        }
    }

    public void invalidateScheduledActionsFor(Address endpoint, Set<Integer> threadIds) {
        if (!node.joined() || !node.isActive()) return;
        if (setScheduledActions.size() > 0) {
            Iterator<ScheduledAction> it = setScheduledActions.iterator();
            while (it.hasNext()) {
                ScheduledAction sa = it.next();
                Request request = sa.getRequest();
                if (endpoint.equals(request.caller) && threadIds.contains(request.lockThreadId)) {
                    sa.setValid(false);
                    it.remove();
                }
            }
        }
    }

    public void connectionAdded(final Connection connection) {
//        enqueueAndReturn(new Processable() {
//            public void process() {
                MemberImpl member = getMember(connection.getEndPoint());
                if (member != null) {
                    member.didRead();
                }
//            }
//        });
    }

    public void connectionRemoved(Connection connection) {
        logger.log(Level.FINEST, "Connection is removed " + connection.getEndPoint());
        if (!node.joined()) {
            if (getMasterAddress() != null) {
                if (getMasterAddress().equals(connection.getEndPoint())) {
                    node.setMasterAddress(null);
                }
            }
        }
    }

    public void addMember(MemberImpl member) {
        addMembers(member);
    }

    public void addMembers(MemberImpl... members) {
        if (members == null || members.length == 0) return;
        logger.log(Level.FINEST, "ClusterManager adding " + Arrays.toString(members));
//        if (lsMembers.contains(member)) {
//            for (MemberImpl m : lsMembers) {
//                if (m.equals(member)) {
//                    member = m;
//                }
//            }
//            mapMembers.put(member.getAddress(), member);
//        } else {
//            lsMembers.add(member);
//            mapMembers.put(member.getAddress(), member);
//            if (!member.isLiteMember()) {
//                dataMemberCount.increment();
//            }
//        }
        lock.lock();
        try {
            Map<Address, MemberImpl> memberMap = membersRef.get();
            if (memberMap == null) {
                memberMap = new LinkedHashMap<Address, MemberImpl>();  // ! ORDERED !
            } else {
                memberMap = new LinkedHashMap<Address, MemberImpl>(memberMap);  // ! ORDERED !
            }

            for (MemberImpl member : members) {
                MemberImpl oldMember = memberMap.remove(member.getAddress());
                if (oldMember != null && !oldMember.isLiteMember()) {
                    dataMemberCount.decrementAndGet();
                }
                memberMap.put(member.getAddress(), member);
                if (!member.isLiteMember()) {
                    dataMemberCount.incrementAndGet();
                }
            }
            membersRef.set(Collections.unmodifiableMap(memberMap));
            getMembers(); // make values() to be cached
        } finally {
            lock.unlock();
        }
    }

    public void removeMember(MemberImpl member) {
//        checkServiceThread();
        logger.log(Level.FINEST, "ClusterManager removing  " + member);
//        mapMembers.remove(member.getAddress());
//        lsMembers.remove(member);
//        if (!member.isLiteMember()) {
//            dataMemberCount.decrementAndGet();
//        }
        lock.lock();
        try {
            Map<Address, MemberImpl> members = membersRef.get();
            if (members != null && members.containsKey(member.getAddress())) {
                members = new LinkedHashMap<Address, MemberImpl>(members);  // ! ORDERED !
                members.remove(member.getAddress());
                if (!member.isLiteMember()) {
                    dataMemberCount.decrementAndGet();
                }
                membersRef.set(Collections.unmodifiableMap(members));
                getMembers(); // make values() to be cached
            }
        } finally {
            lock.unlock();
        }
    }

    protected MemberImpl createMember(Address address, NodeType nodeType, String nodeUuid, String ipV6ScopeId) {
        address.setScopeId(ipV6ScopeId);
        return new MemberImpl(address, thisAddress.equals(address), nodeType, nodeUuid);
    }

    public MemberImpl getMember(Address address) {
        if (address == null) {
            return null;
        }
//        return mapMembers.get(address);
        return membersRef.get().get(address);
    }

//    final public void createAndAddMember(Address address, NodeType nodeType, String nodeUuid) {
////        checkServiceThread();
//        if (address == null) {
//            logger.log(Level.FINEST, "Address cannot be null");
//            return;
//        }
//        lock.lock();
//        try {
//            MemberImpl member = getMember(address);
//            if (member == null) {
//                member = createMember(address, nodeType, nodeUuid, thisAddress.getScopeId());
//            }
//            addMembers(member);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    public Collection<MemberImpl> getMembers() {
        final Map<Address, MemberImpl> map = membersRef.get();
        return map != null ? map.values() : null;
    }

    public int getDataMemberCount() {
        return dataMemberCount.get();
    }

    public void stop() {
        if (setJoins != null) {
            setJoins.clear();
        }
        timeToStartJoin = 0;
//        if (lsMembers != null) {
//            lsMembers.clear();
//        }
        membersRef.set(null);
        dataMemberCount.set(0);
//        if (mapMembers != null) {
//            mapMembers.clear();
//        }
//        if (lsMembersBefore != null) {
//            lsMembersBefore.clear();
//        }
        if (mapCalls != null) {
            mapCalls.clear();
        }
    }
}
