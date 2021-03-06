package test.bftsmart.leaderchange;

import bftsmart.communication.MessageHandler;
import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.communication.server.ServersCommunicationLayer;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.leaderchange.HeartBeatMessage;
import bftsmart.tom.leaderchange.LCMessage;
import bftsmart.tom.leaderchange.LeaderResponseMessage;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

/**
 * @Author: zhangshuang
 * @Date: 2020/3/18 1:57 PM
 * Version 1.0
 */
public class ConsensusTest_ {

    private static final ExecutorService nodeStartPools = Executors.newCachedThreadPool();

    private ServiceReplica[] serviceReplicas;

    private TestNodeServer[] serverNodes;

//    private HeartBeatTimer[] mockHbTimers;

    private ServerCommunicationSystem[] serverCommunicationSystems;

    private  static int clientProcId = 11000;

    private AsynchServiceProxy clientProxy;

    private byte[] bytes;

    @Before
    public void createClient() {
        clientProxy = new AsynchServiceProxy(clientProcId);
        Random random = new Random();
        bytes = new byte[4];
        random.nextBytes(bytes);

    }

    /**
     * simple test when consensus and heart beat all run in 4 nodes
     */
    @Test
    public void test4NodeNormalConsensus() {

        int nodeNum = 4;

        initNode(nodeNum);

        //simple send msg test
        clientProxy.invokeOrdered(bytes);

        try {
            System.out.println("-- client send finish --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 开始进行共识，之后领导者异常，然后领导者恢复
     */
    @Test
    public void test4NodeButLeaderExceptionThenResume() {
        int nodeNums = 4;
        int consensusMsgNum = 10;

        initNode(nodeNums);

        for (int i = 0; i < consensusMsgNum; i++ ) {
            clientProxy.invokeOrdered(bytes);
        }

        try {
            // 延时，等待消息被处理完
            Thread.sleep(20000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        MessageHandler mockMessageHandler = stopNode(0);

        // 重启之前领导者心跳服务
        restartLeaderHeartBeat(serviceReplicas, 0);

        System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", 0);

        // 重置mock操作
        reset(mockMessageHandler);

        try {
            System.out.println("-- leader node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 开始进行共识，之后领导者异常，然后领导者恢复
     * 然后再一次出现该现象
     */
    @Test
    public void test4NodeLoopLeaderExceptionAndCannotReceiveTOMMessageThenResume() {
        int nodeNums = 4;

        initNode(nodeNums);

        Executors.newSingleThreadExecutor().execute(() -> {
            // 假设有10000笔消息
            for (int i = 0; i < 10000; i++ ) {
                clientProxy.invokeOrdered(bytes);
                if (i % 10 == 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });


        try {
            // 仅仅延时
            Thread.sleep(5000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            MockHandlers mockHandlers = stopNodeAndStopReceiveTOMMessage(index);

            MessageHandler mockMessageHandler = mockHandlers.messageHandler;

            TOMLayer mockTomlayer = mockHandlers.tomLayer;

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);
            reset(mockTomlayer);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- leader node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 开始进行共识，之后领导者异常，然后领导者恢复
     * 然后再一次出现该现象
     */
    @Test
    public void test4NodeLoopLeaderExceptionThenResume() {
        int nodeNums = 4;

        initNode(nodeNums);

        Executors.newSingleThreadExecutor().execute(() -> {
            // 假设有10000笔消息
            for (int i = 0; i < 10000; i++ ) {
                clientProxy.invokeOrdered(bytes);
                if (i % 10 == 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });


        try {
            // 仅仅延时
            Thread.sleep(5000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- leader node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 开始进行共识，之后领导者异常，然后领导者恢复
     * 然后再一次出现该现象
     */
    @Test
    public void test4NodeBut2LeaderExceptionThenResume() {
        int nodeNums = 4;
        int consensusMsgNum = 10;

        initNode(nodeNums);

        for (int i = 0; i < consensusMsgNum; i++ ) {
            clientProxy.invokeOrdered(bytes);
        }

        try {
            // 延时，等待消息被处理完
            Thread.sleep(10000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < 2; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- leader node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    /**
     * 开始一段时间正常共识，然后领导者异常循环切换
     */
    @Test
    public void test4NodeFirstNormalConsensusThenLeaderPollException() {
        int nodeNums = 4;
        int consensusMsgNum = 10;

        initNode(nodeNums);

        //正常共识
        for (int i = 0; i < consensusMsgNum; i++ ) {
            clientProxy.invokeOrdered(bytes);
        }

        //领导者轮询异常
        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 正常共识，领导者轮询异常交替进行
     */
    @Test
    public void test4NodeNormalConsensusAndLeadePollExceptionAlter() {
        int nodeNums = 4;
        int consensusMsgNum = 10;

        initNode(nodeNums);

        //正常共识
        for (int i = 0; i < consensusMsgNum; i++ ) {
            clientProxy.invokeOrdered(bytes);
        }

        //领导者轮询异常
        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("-- The first alter complete! --");

        //正常共识
        for (int i = 0; i < consensusMsgNum; i++ ) {
            clientProxy.invokeOrdered(bytes);
        }

        //领导者轮询异常
        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    //在有大批消息共识的过程中通过简单的停止领导者心跳触发领导者切换
    @Test
    public void OneTimeSimpleStopLeaderHbDuringConsensus() {
        int nodeNums = 4;
        int consensusMsgNum = 10000;

        initNode(nodeNums);

        nodeStartPools.execute(() -> {
            for (int i = 0; i < consensusMsgNum; i++) {
                clientProxy.invokeOrdered(bytes);
            }
        });

        stopLeaderHeartBeat(serviceReplicas);

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 领导者发送Propose消息之前网络异常，也就是说上一轮处理结束后，领导者异常
     */
    @Test
    public void oneTimeLeaderChangeDuringConsensusWhenProposeUnSend() throws InterruptedException {

        int nodeNums = 4;
        int consensusMsgNum = 1000;

        initNode(nodeNums);

        nodeStartPools.execute(() -> {
            for (int i = 0; i < consensusMsgNum; i++) {
                if (i % 10 == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                clientProxy.invokeOrdered(bytes);
            }
        });

        Thread.sleep(5000);

        //1节点进行领导者切换
        for (int i = 0; i < 1; i++) {

            final int index = i;

            MockHandlers mockHandlers = stopNodeAndStopProposeMsg(index);

            MessageHandler mockMessageHandler = mockHandlers.messageHandler;

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);
            reset(mockHandlers.serversCommunicationLayer);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    //在有大批消息共识的过程中触发一次领导者异常与恢复
    @Test
    public void OneTimeLeaderChangeDuringConsensus() {

        int nodeNums = 4;
        int consensusMsgNum = 10000;

        initNode(nodeNums);

        nodeStartPools.execute(() -> {
           for (int i = 0; i < consensusMsgNum; i++) {
               clientProxy.invokeOrdered(bytes);
           }
        });

        //1节点进行领导者切换
        for (int i = 0; i < 1; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    //在有大批消息共识的过程中触发两次领导者切换
    @Test
    public void TwoTimesleaderChangeDuringConsensus() {

        int nodeNums = 4;
        int consensusMsgNum = 5000;

        initNode(nodeNums);

        nodeStartPools.execute(() -> {
            for (int i = 0; i < consensusMsgNum; i++) {
                clientProxy.invokeOrdered(bytes);
            }
        });

        //1,2节点分别进行领导者切换
        for (int i = 0; i < 2; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


//    // client send
//    @Test
//    public void clientSend() {
//
//        int consensusNum = 2000;
//        //正常共识
//        for (int i = 0; i < consensusNum; i++ ) {
//            clientProxy.invokeOrdered(bytes);
//        }
//    }

    private MockHandlers stopNodeAndStopReceiveTOMMessage(final int index) {
        // 第一个节点持续异常
        // 重新设置leader的消息处理方式
        MessageHandler mockMessageHandler = spy(serverCommunicationSystems[index].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage) {
                        // 走我们设计的逻辑，即不处理
                    } else if (obj instanceof LeaderResponseMessage) {
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

        // spy 客户端发送的消息
        TOMLayer mockTomlayer = spy(serviceReplicas[index].getTomLayer());

        // 重新设置tomlayer处理客户端消息逻辑
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {

                Object[] objs = invocationOnMock.getArguments();

                Object obj = objs[0];

                if (obj instanceof TOMMessage) {

                    TOMMessage msg = (TOMMessage) obj;
//                    System.out.printf("I am [%s] receive tommessage -> %s \r\n", index, msg);
                }
                return null;
            }
        }).when(mockTomlayer).requestReceived(any());

        // 重设receiver
        serverCommunicationSystems[index].setRequestReceiver(mockTomlayer);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

        try {
            // 休眠40s，等待领导者切换完成
            Thread.sleep(40000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        MockHandlers mockHandlers = new MockHandlers();

        mockHandlers.setMessageHandler(mockMessageHandler);
        mockHandlers.setTomLayer(mockTomlayer);

        return mockHandlers;
    }

    private MockHandlers stopNodeAndStopProposeMsg(final int index) {
        // 第一个节点持续异常
        // 重新设置leader的消息处理方式
        MessageHandler mockMessageHandler = spy(serverCommunicationSystems[index].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage || obj instanceof ConsensusMessage) {
                        // 走我们设计的逻辑，即不处理
                    } else if (obj instanceof LeaderResponseMessage) {
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

        ServersCommunicationLayer serversCommunicationLayer = spy(serverCommunicationSystems[index].getServersConn());

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 3) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[1];
                    if (obj instanceof ConsensusMessage) {
                        //
                        ConsensusMessage cmsg = (ConsensusMessage) obj;
                        if (cmsg.getType() == MessageFactory.PROPOSE) {
                            // 不处理
                        } else {
                            invocationOnMock.callRealMethod();
                        }
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }

                return null;
            }
        }).when(serversCommunicationLayer).send(any(), any(), anyBoolean());

        serverCommunicationSystems[index].setServersConn(serversCommunicationLayer);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

        try {
            // 休眠40s，等待领导者切换完成
            Thread.sleep(40000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        MockHandlers mockHandlers = new MockHandlers();

        mockHandlers.setMessageHandler(mockMessageHandler);
        mockHandlers.setServersCommunicationLayer(serversCommunicationLayer);

        return mockHandlers;
    }

    private MessageHandler stopNode(final int index) {
        // 第一个节点持续异常
        // 重新设置leader的消息处理方式
        MessageHandler mockMessageHandler = spy(serverCommunicationSystems[index].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage) {
                        // 走我们设计的逻辑，即不处理
                    } else if (obj instanceof LeaderResponseMessage) {
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

        try {
            // 休眠40s，等待领导者切换完成
            Thread.sleep(40000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mockMessageHandler;
    }


    private void initNode(int nodeSize) {

        // 首先删除view，然后修改配置文件
        try {
            String path = HeartBeatForOtherSizeTest_.class.getResource("/").toURI().getPath();
            String dirPath = new File(path).getParentFile().getParentFile().getPath() + File.separator + "config";
            // 删除view
            new File(dirPath + File.separator + "currentView").delete();
            // 删除system文件
            new File(dirPath + File.separator + "system.config").delete();

            // 根据nodeSize，重新copy一份system.config文件
            File needSystemConfig = new File(dirPath + File.separator + "system_" + nodeSize + ".config");

            // copy一份system.config
            FileUtils.copyFile(needSystemConfig, new File(dirPath + File.separator + "system.config"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        CountDownLatch servers = new CountDownLatch(nodeSize);

        serviceReplicas = new ServiceReplica[nodeSize];

        serverNodes = new TestNodeServer[nodeSize];

//        mockHbTimers = new HeartBeatTimer[nodeSize];

        serverCommunicationSystems = new ServerCommunicationSystem[nodeSize];

        //start nodeSize node servers
        for (int i = 0; i < nodeSize ; i++) {
            serverNodes[i] = new TestNodeServer(i);
            TestNodeServer node = serverNodes[i];
            nodeStartPools.execute(() -> {
                node.startNode();
                servers.countDown();
            });
        }

        try {
            servers.await();
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < nodeSize; i++) {
            serviceReplicas[i] = serverNodes[i].getReplica();
//            mockHbTimers[i] = serviceReplicas[i].getHeartBeatTimer();
            serverCommunicationSystems[i] = serviceReplicas[i].getServerCommunicationSystem();
        }
    }

    private void stopLeaderHeartBeat(ServiceReplica[] serviceReplicas) {

        int leadId = serviceReplicas[0].getTomLayer().getExecManager().getCurrentLeader();

        serviceReplicas[leadId].getTomLayer().heartBeatTimer.stopAll();
    }

    private void restartLeaderHeartBeat(ServiceReplica[] serviceReplicas, int node) {

        int leadId = serviceReplicas[node].getTomLayer().getExecManager().getCurrentLeader();

        System.out.printf("my new leader = %s \r\n", leadId);

        serviceReplicas[leadId].getTomLayer().heartBeatTimer.restart();
    }

    private static class MockHandlers {

        private MessageHandler messageHandler;

        private TOMLayer tomLayer;

        private ServersCommunicationLayer serversCommunicationLayer;

        public void setMessageHandler(MessageHandler messageHandler) {
            this.messageHandler = messageHandler;
        }

        public void setTomLayer(TOMLayer tomLayer) {
            this.tomLayer = tomLayer;
        }

        public void setServersCommunicationLayer(ServersCommunicationLayer serversCommunicationLayer) {
            this.serversCommunicationLayer = serversCommunicationLayer;
        }
    }
}
