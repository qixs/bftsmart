/**
 * Copyright (c) 2007-2009 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags
 * 
 * This file is part of SMaRt.
 * 
 * SMaRt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SMaRt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with SMaRt.  If not, see <http://www.gnu.org/licenses/>.
 */

package navigators.smart.tom.core;

import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignedObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import navigators.smart.clientsmanagement.ClientsManager;
import navigators.smart.clientsmanagement.RequestList;
import navigators.smart.communication.ServerCommunicationSystem;
import navigators.smart.communication.client.RequestReceiver;
import navigators.smart.paxosatwar.Consensus;
import navigators.smart.paxosatwar.executionmanager.RoundValuePair;
import navigators.smart.paxosatwar.executionmanager.Execution;
import navigators.smart.paxosatwar.executionmanager.ExecutionManager;
import navigators.smart.paxosatwar.executionmanager.LeaderModule;
import navigators.smart.paxosatwar.executionmanager.Round;
import navigators.smart.paxosatwar.roles.Acceptor;
import navigators.smart.reconfiguration.ReconfigurationManager;
import navigators.smart.statemanagment.SMMessage;
import navigators.smart.statemanagment.StateLog;
import navigators.smart.statemanagment.StateManager;
import navigators.smart.statemanagment.TransferableState;
import navigators.smart.tom.TOMRequestReceiver;
import navigators.smart.tom.core.messages.TOMMessage;
import navigators.smart.tom.core.messages.TOMMessageType;
import navigators.smart.tom.core.timer.RequestsTimer;
import navigators.smart.tom.core.timer.messages.ForwardedMessage;
import navigators.smart.tom.util.BatchBuilder;
import navigators.smart.tom.util.BatchReader;
import navigators.smart.tom.util.Logger;
import navigators.smart.tom.util.TOMUtil;
import navigators.smart.tom.leaderchange.LCMessage;
import navigators.smart.tom.leaderchange.CollectData;
import navigators.smart.tom.leaderchange.LCManager;
import navigators.smart.tom.leaderchange.LastEidData;


/**
 * This class implements a thread that uses the PaW algorithm to provide the application
 * a layer of total ordered messages
 */
public final class TOMLayer extends Thread implements RequestReceiver {

    //other components used by the TOMLayer (they are never changed)
    public ExecutionManager execManager; // Execution manager
    public LeaderModule lm; // Leader module
    public Acceptor acceptor; // Acceptor role of the PaW algorithm
    private ServerCommunicationSystem communication; // Communication system between replicas
    //private OutOfContextMessageThread ot; // Thread which manages messages that do not belong to the current execution
    private DeliveryThread dt; // Thread which delivers total ordered messages to the appication
    
    /** Manage timers for pending requests */
    public RequestsTimer requestsTimer;
    /** Store requests received but still not ordered */
    public ClientsManager clientsManager;
    /** The id of the consensus being executed (or -1 if there is none) */
    private int inExecution = -1;
    private int lastExecuted = -1;
    
    private MessageDigest md;
    private Signature engine;

    //the next two are used to generate non-deterministic data in a deterministic way (by the leader)
    private BatchBuilder bb = new BatchBuilder();

    /* The locks and conditions used to wait upon creating a propose */
    private ReentrantLock leaderLock = new ReentrantLock();
    private Condition iAmLeader = leaderLock.newCondition();
    private ReentrantLock messagesLock = new ReentrantLock();
    private Condition haveMessages = messagesLock.newCondition();
    private ReentrantLock proposeLock = new ReentrantLock();
    private Condition canPropose = proposeLock.newCondition();

    /*** ISTO E CODIGO DO JOAO, RELACIONADO COM A TROCA DE LIDER */
    private LCManager lcManager;
    /*************************************************************/

    /* flag that indicates that the lader changed between the last propose and
    this propose. This flag is changed on updateLeader (to true) and decided
    (to false) and used in run.*/
    private boolean leaderChanged = true;

    private PrivateKey prk;
    
    private ReconfigurationManager reconfManager;
    
    /**
     * Creates a new instance of TOMulticastLayer
     * @param manager Execution manager
     * @param receiver Object that receives requests from clients
     * @param lm Leader module
     * @param a Acceptor role of the PaW algorithm
     * @param cs Communication system between replicas
     * @param conf TOM configuration
     */
    public TOMLayer(ExecutionManager manager,
            TOMRequestReceiver receiver,
            LeaderModule lm,
            Acceptor a,
            ServerCommunicationSystem cs,
            ReconfigurationManager recManager) {

        super("TOM Layer");

        this.execManager = manager;
        this.lm = lm;
        this.acceptor = a;
        this.communication = cs;
        this.reconfManager = recManager;

        //do not create a timer manager if the timeout is 0
        if (reconfManager.getStaticConf().getRequestTimeout() == 0){
            this.requestsTimer = null;
        }
        else this.requestsTimer = new RequestsTimer(this, reconfManager.getStaticConf().getRequestTimeout()); // Create requests timers manager (a thread)

        this.clientsManager = new ClientsManager(reconfManager, requestsTimer); // Create clients manager

        try {
            this.md = MessageDigest.getInstance("MD5"); // TODO: nao devia ser antes SHA?
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }

        try {
            this.engine = Signature.getInstance("SHA1withRSA");
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.prk = reconfManager.getStaticConf().getRSAPrivateKey();

        /*** ISTO E CODIGO DO JOAO, RELACIONADO COM A TROCA DE LIDER */
        this.lcManager = new LCManager(this,recManager, md);
        /*************************************************************/

        this.dt = new DeliveryThread(this, receiver, this.reconfManager); // Create delivery thread
        this.dt.start();

        /** ISTO E CODIGO DO JOAO, PARA TRATAR DOS CHECKPOINTS E TRANSFERENCIA DE ESTADO*/
        this.stateManager = new StateManager(this.reconfManager);
        /*******************************************************/
    }

    ReentrantLock hashLock = new ReentrantLock();

    /**
     * Computes an hash for a TOM message
     * @param message
     * @return Hash for teh specified TOM message
     */
    public final byte[] computeHash(byte[] data) {
        byte[] ret = null;
        hashLock.lock();
        ret = md.digest(data);
        hashLock.unlock();

        return ret;
    }

    public SignedObject sign(Serializable obj) {
        try {
            return new SignedObject(obj, prk, engine);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return null;
        }
    }

    /**
     * Verifies the signature of a signed object
     * @param so Signed object to be verified
     * @param sender Replica id that supposably signed this object
     * @return True if the signature is valid, false otherwise
     */
    public boolean verifySignature(SignedObject so, int sender) {
        try {
            return so.verify(reconfManager.getStaticConf().getRSAPublicKey(sender), engine);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Retrieve Communication system between replicas
     * @return Communication system between replicas
     */
    public ServerCommunicationSystem getCommunication() {
        return this.communication;
    }

    public void imAmTheLeader() {
        leaderLock.lock();
        iAmLeader.signal();
        leaderLock.unlock();
    }

    /**
     * Sets which consensus was the last to be executed
     * @param last ID of the consensus which was last to be executed
     */
    public void setLastExec(int last) {
        this.lastExecuted = last;
    }

    /**
     * Gets the ID of the consensus which was established as the last executed
     * @return ID of the consensus which was established as the last executed
     */
    public int getLastExec() {
        return this.lastExecuted;
    }

    /**
     * Sets which consensus is being executed at the moment
     *
     * @param inEx ID of the consensus being executed at the moment
     */
    public void setInExec(int inEx) {
        Logger.println("(TOMLayer.setInExec) modifying inExec from " + this.inExecution + " to " + inEx);

        proposeLock.lock();
        this.inExecution = inEx;
        if (inEx == -1  && !isRetrievingState()) {
            canPropose.signalAll();
        }
        proposeLock.unlock();
    }

    /**
     * This method blocks until the PaW algorithm is finished
     */
    public void waitForPaxosToFinish() {
        proposeLock.lock();
        canPropose.awaitUninterruptibly();
        proposeLock.unlock();
    }

    /**
     * Gets the ID of the consensus currently beign executed
     *
     * @return ID of the consensus currently beign executed (if no consensus ir executing, -1 is returned)
     */
    public int getInExec() {
        return this.inExecution;
    }

    /**
     * This method is invoked by the comunication system to deliver a request.
     * It assumes that the communication system delivers the message in FIFO
     * order.
     *
     * @param msg The request being received
     */
    @Override
    public void requestReceived(TOMMessage msg) {
        /**********************************************************/
        /********************MALICIOUS CODE************************/
        /**********************************************************/
        //first server always ignores messages from the first client (with n=4)
        /*
        if (conf.getProcessId() == 0 && msg.getSender() == 4) {
        return;
        }
      */
        /**********************************************************/
        /**********************************************************/
        /**********************************************************/

        // check if this request is valid and add it to the client' pending requests list
        boolean readOnly = (msg.getReqType() == TOMMessageType.READONLY_REQUEST);
        if (clientsManager.requestReceived(msg, true, !readOnly, communication)) {
            if (readOnly) {
                dt.deliverUnordered(msg);
            } else {
                messagesLock.lock();
                haveMessages.signal();
                messagesLock.unlock();
            }
        } else {
            Logger.println("(TOMLayer.requestReceive) the received TOMMessage " + msg + " was discarded.");
        }

    }

    /**
     * Creates a value to be proposed to the acceptors. Invoked if this replica is the leader
     * @return A value to be proposed to the acceptors
     */
    private byte[] createPropose(Consensus cons) {
        // Retrieve a set of pending requests from the clients manager
        RequestList pendingRequests = clientsManager.getPendingRequests();

        int numberOfMessages = pendingRequests.size(); // number of messages retrieved
        int numberOfNonces = this.reconfManager.getStaticConf().getNumberOfNonces(); // ammount of nonces to be generated

        //for benchmarking
        cons.firstMessageProposed = pendingRequests.getFirst();
        cons.firstMessageProposed.consensusStartTime = System.nanoTime();
        cons.batchSize = numberOfMessages;

        Logger.println("(TOMLayer.run) creating a PROPOSE with " + numberOfMessages + " msgs");

        return bb.makeBatch(pendingRequests, numberOfNonces, System.currentTimeMillis(),reconfManager);
    }
    /**
     * This is the main code for this thread. It basically waits until this replica becomes the leader,
     * and when so, proposes a value to the other acceptors
     */
    @Override
    public void run() {
        Logger.println("Running."); // TODO: isto n podia passar para fora do ciclo?
        while (true) {
            // blocks until this replica learns to be the leader for the current round of the current consensus
            leaderLock.lock();
            Logger.println("Next leader for eid=" + (getLastExec() + 1) + ": " + lm.getCurrentLeader());
            
            //******* EDUARDO BEGIN **************//
            if (/*lm.getLeader(getLastExec() + 1, 0)*/ lm.getCurrentLeader() != this.reconfManager.getStaticConf().getProcessId()) {
                iAmLeader.awaitUninterruptibly();
                waitForPaxosToFinish();
            }
            //******* EDUARDO END **************//
            
            leaderLock.unlock();
            Logger.println("(TOMLayer.run) I'm the leader.");

            // blocks until there are requests to be processed/ordered
            messagesLock.lock();
            if (!clientsManager.havePendingRequests()) {
                haveMessages.awaitUninterruptibly();
            }
            messagesLock.unlock();
            Logger.println("(TOMLayer.run) There are messages to be ordered.");

            // blocks until the current consensus finishes
            proposeLock.lock();
            if (getInExec() != -1 && !leaderChanged) { //there is some consensus running and the leader not changed
                Logger.println("(TOMLayer.run) Waiting for consensus " + getInExec() + " termination.");
                canPropose.awaitUninterruptibly();
            }
            proposeLock.unlock();

            Logger.println("(TOMLayer.run) I can try to propose.");
            if ((lm.getCurrentLeader() == this.reconfManager.getStaticConf().getProcessId()) && //I'm the leader
                    (clientsManager.havePendingRequests()) && //there are messages to be ordered
                    (getInExec() == -1 || leaderChanged)) { //there is no consensus in execution

                leaderChanged = false;

                // Sets the current execution
                int execId = getLastExec() + 1;
                setInExec(execId);

                execManager.getProposer().startExecution(execId,
                        createPropose(execManager.getExecution(execId).getLearner()));
            }
        }
    }

    /**
     * Called by the current consensus's execution, to notify the TOM layer that a value was decided
     * @param cons The decided consensus
     */
    public void decided(Consensus cons) {
        this.dt.delivery(cons); // Delivers the consensus to the delivery thread
    }

    /**
     * Verify if the value being proposed for a round is valid. It verifies the
     * client signature of all batch requests.
     *
     * TODO: verify timestamps and nonces
     *
     * @param round the Round for which this value is being proposed
     * @param proposedValue the value being proposed
     * @return
     */
    public TOMMessage[] checkProposedValue(byte[] proposedValue) {
        Logger.println("(TOMLayer.isProposedValueValid) starting");

        BatchReader batchReader = new BatchReader(proposedValue, 
                this.reconfManager.getStaticConf().getUseSignatures() == 1);

        TOMMessage[] requests = null;

        try {
            //deserialize the message
            //TODO: verify Timestamps and Nonces
            requests = batchReader.deserialiseRequests(this.reconfManager);

            for (int i = 0; i < requests.length; i++) {
                //notifies the client manager that this request was received and get
                //the result of its validation
                if (!clientsManager.requestReceived(requests[i], false)) {
                    clientsManager.getClientsLock().unlock();
                    Logger.println("(TOMLayer.isProposedValueValid) finished, return=false");
                    System.out.println("failure in deserialize batch");
                    return null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            clientsManager.getClientsLock().unlock();
            Logger.println("(TOMLayer.isProposedValueValid) finished, return=false");
            return null;
        }
        Logger.println("(TOMLayer.isProposedValueValid) finished, return=true");

        return requests;
    }
    public void forwardRequestToLeader(TOMMessage request) {
        int leaderId = lm.getCurrentLeader();
        System.out.println("(TOMLayer.forwardRequestToLeader) forwarding " + request + " to " + leaderId);
        
            //******* EDUARDO BEGIN **************//
        communication.send(new int[]{leaderId}, 
                new ForwardedMessage(this.reconfManager.getStaticConf().getProcessId(), request));
            //******* EDUARDO END **************//
    }

    /** ISTO E CODIGO DO JOAO, PARA TRATAR DOS CHECKPOINTS */
    private StateManager stateManager = null;
    private ReentrantLock lockState = new ReentrantLock();
    private ReentrantLock lockTimer = new ReentrantLock();
    private Timer stateTimer = null;

    public void saveState(byte[] state, int lastEid, int decisionRound, int leader) {

        StateLog log = stateManager.getLog();

        lockState.lock();

        Logger.println("(TOMLayer.saveState) Saving state of EID " + lastEid + ", round " + decisionRound + " and leader " + leader);

        log.newCheckpoint(state, computeHash(state));
        log.setLastEid(-1);
        log.setLastCheckpointEid(lastEid);
        log.setLastCheckpointRound(decisionRound);
        log.setLastCheckpointLeader(leader);

        /************************* TESTE *************************
        System.out.println("[TOMLayer.saveState]");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (log.getState()[i] & 0x000000FF) << shift;
        }
        System.out.println("//////////////////CHECKPOINT//////////////////////");
        System.out.println("Estado: " + value);
        System.out.println("Checkpoint: " + log.getLastCheckpointEid());
        System.out.println("Ultimo EID: " + log.getLastEid());
        System.out.println("//////////////////////////////////////////////////");
        System.out.println("[/TOMLayer.saveState]");
        /************************* TESTE *************************/
        
        lockState.unlock();

        Logger.println("(TOMLayer.saveState) Finished saving state of EID " + lastEid + ", round " + decisionRound + " and leader " + leader);
    }
    public void saveBatch(byte[] batch, int lastEid, int decisionRound, int leader) {

        StateLog log = stateManager.getLog();

        lockState.lock();

        Logger.println("(TOMLayer.saveBatch) Saving batch of EID " + lastEid + ", round " + decisionRound + " and leader " + leader);

        log.addMessageBatch(batch, decisionRound, leader);
        log.setLastEid(lastEid);

        /************************* TESTE *************************
        System.out.println("[TOMLayer.saveBatch]");
        byte[][] batches = log.getMessageBatches();
        int count = 0;
        for (int i = 0; i < batches.length; i++)
            if (batches[i] != null) count++;

        System.out.println("//////////////////////BATCH///////////////////////");
        //System.out.println("Total batches (according to StateManager): " + stateManager.getLog().getNumBatches());
        System.out.println("Total batches (actually counted by this code): " + count);
        System.out.println("Ultimo EID: " + log.getLastEid());
        //System.out.println("Espaco restante para armazenar batches: " + (stateManager.getLog().getMessageBatches().length - count));
        System.out.println("//////////////////////////////////////////////////");
        System.out.println("[/TOMLayer.saveBatch]");
        /************************* TESTE *************************/

        lockState.unlock();

        Logger.println("(TOMLayer.saveBatch) Finished saving batch of EID " + lastEid + ", round " + decisionRound + " and leader " + leader);
    }

    /** ISTO E CODIGO DO JOAO, PARA TRATAR DA TRANSFERENCIA DE ESTADO */
    
    public void requestState(int sender, int eid) {

        //******* EDUARDO BEGIN **************//
        if (reconfManager.getStaticConf().isStateTransferEnabled()) {
        //******* EDUARDO END **************//

            Logger.println("(TOMLayer.requestState) The state transfer protocol is enabled");

            if (stateManager.getWaiting() == -1) {

                Logger.println("(TOMLayer.requestState) I'm not waiting for any state, so I will keep record of this message");
                stateManager.addEID(sender, eid);

                /************************* TESTE *************************
                System.out.println("Nao estou a espera");
                System.out.println("Numero de mensagens recebidas para este EID de replicas diferentes: " + stateManager.moreThenF_EIDs(eid));
                /************************* TESTE *************************/

                if (stateManager.getLastEID() < eid && stateManager.moreThenF_EIDs(eid)) {

                    Logger.println("(TOMLayer.requestState) I have now more than " + reconfManager.getCurrentViewF() + " messages for EID " + eid + " which are beyond EID " + stateManager.getLastEID());
                    /************************* TESTE *************************
                    System.out.println("Recebi mais de " + conf.getF() + " mensagens para eid " + eid + " que sao posteriores a " + stateManager.getLastEID());
                    /************************* TESTE *************************/

                    requestsTimer.clearAll();
                    stateManager.setLastEID(eid);
                    stateManager.setWaiting(eid - 1);
                    //stateManager.emptyReplicas(eid);// isto causa uma excepcao

                    SMMessage smsg = new SMMessage(reconfManager.getStaticConf().getProcessId(), 
                            eid - 1, TOMUtil.SM_REQUEST, stateManager.getReplica(), null);
                    communication.send(reconfManager.getCurrentViewOtherAcceptors(), smsg);

                    Logger.println("(TOMLayer.requestState) I just sent a request to the other replicas for the state up to EID " + (eid - 1));

                    TimerTask stateTask =  new TimerTask() {
                        public void run() {

                        lockTimer.lock();

                        Logger.println("(TimerTask.run) Timeout for the replica that was supposed to send the complete state. Changing desired replica.");
                        System.out.println("Timeout no timer do estado!");

                        stateManager.setWaiting(-1);
                        stateManager.changeReplica();
                        stateManager.emptyStates();
                        stateManager.setReplicaState(null);

                        lockTimer.unlock();
                        }
                    };

                    Timer stateTimer = new Timer("state timer");
                    stateTimer.schedule(stateTask,1500);
                    /************************* TESTE *************************

                    System.out.println("Enviei um pedido!");
                    System.out.println("Quem envia: " + smsg.getSender());
                    System.out.println("Que tipo: " + smsg.getType());
                    System.out.println("Que EID: " + smsg.getEid());
                    System.out.println("Ultimo EID: " + stateManager.getLastEID());
                    System.out.println("A espera do EID: " + stateManager.getWaiting());
                    /************************* TESTE *************************/
                }
            }
        }
        else {
            System.out.println("##################################################################################");
            System.out.println("- Ahead-of-time message discarded");
            System.out.println("- If many messages of the same consensus are discarded, the replica can halt!");
            System.out.println("- Try to increase the 'system.paxos.highMarc' configuration parameter.");
            System.out.println("- Last consensus executed: " + lastExecuted);
            System.out.println("##################################################################################");
        }
        /************************* TESTE *************************
        System.out.println("[/TOMLayer.requestState]");
        /************************* TESTE *************************/
    }

    public void SMRequestDeliver(SMMessage msg) {

        //******* EDUARDO BEGIN **************//
        if (reconfManager.getStaticConf().isStateTransferEnabled()) {
        //******* EDUARDO END **************//

            Logger.println("(TOMLayer.SMRequestDeliver) The state transfer protocol is enabled");
            
            lockState.lock();

            Logger.println("(TOMLayer.SMRequestDeliver) I received a state request for EID " + msg.getEid() + " from replica " + msg.getSender());
            /************************* TESTE *************************
            System.out.println("[TOMLayer.SMRequestDeliver]");
            System.out.println("Recebi um pedido de estado!");
            System.out.println("Estado pedido: " + msg.getEid());
            System.out.println("Checkpoint q eu tenho: " + stateManager.getLog().getLastCheckpointEid());
            System.out.println("Ultimo eid q recebi no log: " + stateManager.getLog().getLastEid());
            /************************* TESTE *************************/

            boolean sendState = msg.getReplica() == reconfManager.getStaticConf().getProcessId();
            if (sendState) Logger.println("(TOMLayer.SMRequestDeliver) I should be the one sending the state");

            TransferableState state = stateManager.getLog().getTransferableState(msg.getEid(), sendState);

            lockState.unlock();

            if (state == null) {
                Logger.println("(TOMLayer.SMRequestDeliver) I don't have the state requested :-(");
               /************************* TESTE *************************
               System.out.println("Nao tenho o estado pedido!");
               /************************* TESTE *************************/
              state = new TransferableState();
            }
        
            /************************* TESTE *************************
            else {

                for (int eid = state.getLastCheckpointEid() + 1; eid <= state.getLastEid(); eid++) {
                    byte[] batch = state.getMessageBatch(eid).batch;

                    if (batch == null) System.out.println("isto esta nulo!!!");
                    else System.out.println("isto nao esta nulo");
                
                    BatchReader batchReader = new BatchReader(batch,reconfManager.getStaticConf().getUseSignatures() == 1);
                    TOMMessage[] requests = batchReader.deserialiseRequests(reconfManager);
                    System.out.println("tudo correu bem");
                }
            }
            /************************* TESTE *************************/

            /** CODIGO MALICIOSO, PARA FORCAR A REPLICA ATRASADA A PEDIR O ESTADO A OUTRA DAS REPLICAS */
            //byte[] badState = {127};
            //if (sendState && reconfManager.getStaticConf().getProcessId() == 0) state.setState(badState);
            /*******************************************************************************************/

            int[] targets = { msg.getSender() };
            SMMessage smsg = new SMMessage(reconfManager.getStaticConf().getProcessId(), 
                    msg.getEid(), TOMUtil.SM_REPLY, -1, state);

            // malicious code, to force the replica not to send the state
            //if (reconfManager.getStaticConf().getProcessId() != 0 || !sendState)
            communication.send(targets, smsg);

            Logger.println("(TOMLayer.SMRequestDeliver) I sent the state for checkpoint " + state.getLastCheckpointEid() + " with batches until EID " + state.getLastEid());
            /************************* TESTE *************************
            System.out.println("Quem envia: " + smsg.getSender());
            System.out.println("Que tipo: " + smsg.getType());
            System.out.println("Que EID: " + smsg.getEid());
            //System.exit(0);
            /************************* TESTE *************************/
            /************************* TESTE *************************
            System.out.println("[/TOMLayer.SMRequestDeliver]");
            /************************* TESTE *************************/
        }
    }

    public void SMReplyDeliver(SMMessage msg) {

        /************************* TESTE *************************
        System.out.println("[TOMLayer.SMReplyDeliver]");
        System.out.println("Recebi uma resposta de uma replica!");
        System.out.println("[reply] Esta resposta tem o estado? " + msg.getState().hasState());
        System.out.println("[reply] EID do ultimo checkpoint: " + msg.getState().getLastCheckpointEid());
        System.out.println("[reply] EID do ultimo batch recebido: " + msg.getState().getLastEid());
        if (msg.getState().getMessageBatches() != null)
            System.out.println("[reply] Numero de batches: " + msg.getState().getMessageBatches().length);
        else System.out.println("[reply] Nao ha batches");
        if (msg.getState().getState() != null) {
            System.out.println("[reply] Tamanho do estado em bytes: " + msg.getState().getState().length);

            int value = 0;
            for (int i = 0; i < 4; i++) {
                int shift = (4 - 1 - i) * 8;
                value += (msg.getState().getState()[i] & 0x000000FF) << shift;
            }
            System.out.println("[reply] Valor do estado: " + value);
        }
        else System.out.println("[reply] Nao ha estado");
        /************************* TESTE *************************/
        //******* EDUARDO BEGIN **************//

        lockTimer.lock();
        if (reconfManager.getStaticConf().isStateTransferEnabled()) {
        //******* EDUARDO END **************//

            Logger.println("(TOMLayer.SMReplyDeliver) The state transfer protocol is enabled");
            Logger.println("(TOMLayer.SMReplyDeliver) I received a state reply for EID " + msg.getEid() + " from replica " + msg.getSender());

            if (stateManager.getWaiting() != -1 && msg.getEid() == stateManager.getWaiting()) {

                /************************* TESTE *************************
                System.out.println("A resposta e referente ao eid que estou a espera! (" + msg.getEid() + ")");
                /************************* TESTE *************************/
                Logger.println("(TOMLayer.SMReplyDeliver) The reply is for the EID that I want!");
            
                if (msg.getSender() == stateManager.getReplica() && msg.getState().getState() != null) {
                    Logger.println("(TOMLayer.SMReplyDeliver) I received the state, from the replica that I was expecting");
                    stateManager.setReplicaState(msg.getState().getState());
                    if (stateTimer != null) stateTimer.cancel();
                }

                stateManager.addState(msg.getSender(),msg.getState());

                if (stateManager.moreThanF_Replies()) {

                    Logger.println("(TOMLayer.SMReplyDeliver) I have at least " + reconfManager.getCurrentViewF() + " replies!");
                    /************************* TESTE *************************
                    System.out.println("Ja tenho mais que " + reconfManager.getQuorumF() + " respostas iguais!");
                    /************************* TESTE *************************/

                    TransferableState state = stateManager.getValidHash();

                    int haveState = 0;
                    if (stateManager.getReplicaState() != null) {
                        byte[] hash = null;
                        hash = computeHash(stateManager.getReplicaState());
                        if (state != null) {
                            if (Arrays.equals(hash, state.getStateHash())) haveState = 1;
                            else if (stateManager.getNumValidHashes() > reconfManager.getCurrentViewF()) haveState = -1;

                        }
                    }

                    if (state != null && haveState == 1) {

                        /************************* TESTE *************************
                        System.out.println("As respostas desse estado são validas!");

                        System.out.println("[state] Esta resposta tem o estado? " + state.hasState());
                        System.out.println("[state] EID do ultimo checkpoint: " + state.getLastCheckpointEid());
                        System.out.println("[state] EID do ultimo batch recebido: " + state.getLastEid());
                        if (state.getMessageBatches() != null)
                            System.out.println("[state] Numero de batches: " + state.getMessageBatches().length);
                        else System.out.println("[state] Nao ha batches");
                        if (state.getState() != null) {
                            System.out.println("[state] Tamanho do estado em bytes: " + state.getState().length);

                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                int shift = (4 - 1 - i) * 8;
                                value += (state.getState()[i] & 0x000000FF) << shift;
                            }
                            System.out.println("[state] Valor do estado: " + value);
                        }
                        else System.out.println("[state] Nao ha estado");

                        //System.exit(0);
                        /************************* TESTE *************************/

                        Logger.println("(TOMLayer.SMReplyDeliver) The state of those replies is good!");

                        state.setState(stateManager.getReplicaState());
                    
                        lockState.lock();

                        stateManager.getLog().update(state);

                        /************************* TESTE *************************
                        System.out.println("[log] Estado pedido: " + msg.getEid());
                        System.out.println("[log] EID do ultimo checkpoint: " + stateManager.getLog().getLastCheckpointEid());
                        System.out.println("[log] EID do ultimo batch recebido: " + stateManager.getLog().getLastEid());
                        System.out.println("[log] Numero de batches: " + stateManager.getLog().getNumBatches());
                        if (stateManager.getLog().getState() != null) {
                            System.out.println("[log] Tamanho do estado em bytes: " + stateManager.getLog().getState().length);
                    
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                int shift = (4 - 1 - i) * 8;
                                value += (stateManager.getLog().getState()[i] & 0x000000FF) << shift;
                            }
                            System.out.println("[log] Valor do estado: " + value);
                        }
                        //System.exit(0);
                        /************************* TESTE *************************/

                        lockState.unlock();

                        //System.out.println("Desbloqueei o lock para o log do estado");
                        dt.deliverLock();

                        //System.out.println("Bloqueei o lock entre esta thread e a delivery thread");

                        //ot.OutOfContextLock();

                        //System.out.println("Bloqueei o lock entre esta thread e a out of context thread");

                        stateManager.setWaiting(-1);

                        //System.out.println("Ja nao estou a espera de nenhum estado, e vou actualizar-me");

                        dt.update(state);
                        processOutOfContext();

                        dt.canDeliver();

                        //ot.OutOfContextUnlock();
                        dt.deliverUnlock();
                    
                        stateManager.emptyStates();
                        stateManager.setReplicaState(null);

                        System.out.println("Actualizei o estado!");

                    //******* EDUARDO BEGIN **************//
                    } else if (state == null && (reconfManager.getCurrentViewN() / 2) < stateManager.getReplies()) {
                    //******* EDUARDO END **************//
                        
                        Logger.println("(TOMLayer.SMReplyDeliver) I have more than " + 
                                (reconfManager.getCurrentViewN() / 2) + " messages that are no good!");
                        /************************* TESTE *************************
                        System.out.println("Tenho mais de 2F respostas que nao servem para nada!");
                        //System.exit(0);
                        /************************* TESTE *************************/

                        stateManager.setWaiting(-1);
                        stateManager.emptyStates();
                        stateManager.setReplicaState(null);

                        if (stateTimer != null) stateTimer.cancel();
                    } else if (haveState == -1) {

                        Logger.println("(TOMLayer.SMReplyDeliver) The replica from which I expected the state, sent one which doesn't match the hash of the others, or it never sent it at all");

                        stateManager.setWaiting(-1);
                        stateManager.changeReplica();
                        stateManager.emptyStates();
                        stateManager.setReplicaState(null);

                        if (stateTimer != null) stateTimer.cancel();
                    }
                }
            }
        }
        lockTimer.unlock();
        /************************* TESTE *************************
        System.out.println("[/TOMLayer.SMReplyDeliver]");
        /************************* TESTE *************************/
    }

    public boolean isRetrievingState() {
        //lockTimer.lock();
        boolean result =  stateManager != null && stateManager.getWaiting() != -1;
        //lockTimer.unlock();

        return result;
    }

    public void setNoExec() {
        Logger.println("(TOMLayer.setNoExec) modifying inExec from " + this.inExecution + " to " + -1);

        proposeLock.lock();
        this.inExecution = -1;
        //ot.addUpdate();
        canPropose.signalAll();
        proposeLock.unlock();
    }

    /********************************************************/

    public void processOutOfContext() {
        for (int nextExecution = getLastExec() + 1;
                execManager.receivedOutOfContextPropose(nextExecution);
                nextExecution = getLastExec() + 1) {
            
            execManager.processOutOfContextPropose(execManager.getExecution(nextExecution));
        }
    }
    /********************************************************************/

    /*** ISTO E CODIGO DO JOAO, RELACIONADO COM A TROCA DE LIDER */

    /**
     * Este metodo e invocado quando ha um timeout e o request ja foi re-encaminhado para o lider
     * @param requestList Lista de pedidos que a replica quer ordenar mas nao conseguiu
     */
    public void triggerTimeout(List<TOMMessage> requestList) {

        ObjectOutputStream out = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        lcManager.nexttsLock();
        lcManager.lasttsLock();

        // ainda nao estou na fase de troca de lider?
        if (lcManager.getNextts() == lcManager.getLastts()) {

                lcManager.setNextts(lcManager.getLastts() + 1); // definir proximo timestamp

                int ts = lcManager.getNextts();

                lcManager.nexttsUnlock();
                lcManager.lasttsUnlock();

                // guardar mensagens para ordenar
                lcManager.setCurrentRequestTimedOut(requestList);

                // guardar informacao da mensagem que vou enviar
                lcManager.StopsLock();
                lcManager.addStop(ts, this.reconfManager.getStaticConf().getProcessId());
                lcManager.StopsUnlock();

                execManager.stop(); // parar execucao do consenso

                try { // serializar conteudo a enviar na mensagem STOP
                    out = new ObjectOutputStream(bos);

                    if (lcManager.getCurrentRequestTimedOut() != null) {

                        //TODO: Se isto estiver a null, e porque nao houve timeout. Fazer o q?
                        byte[] msgs = bb.makeBatch(lcManager.getCurrentRequestTimedOut(), 0, 0, reconfManager);
                        List<TOMMessage> temp = lcManager.getCurrentRequestTimedOut();
                        out.writeBoolean(true);
                        out.writeObject(msgs);
                    }
                    else {
                        out.writeBoolean(false);
                    }

                    byte[] payload = bos.toByteArray();

                    out.flush();
                    bos.flush();

                    out.close();
                    bos.close();

                    // enviar mensagem STOP
                    communication.send(this.reconfManager.getCurrentViewOtherAcceptors(),
                    new LCMessage(this.reconfManager.getStaticConf().getProcessId(), TOMUtil.STOP, ts, payload));

                } catch (IOException ex) {
                    java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                } finally {
                    try {
                        out.close();
                        bos.close();
                    } catch (IOException ex) {
                        java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }

                evaluateStops(ts); // avaliar mensagens stops

        }

        else {
                lcManager.nexttsUnlock();
                lcManager.lasttsUnlock();
        }
    }

    // este metodo e invocado aquando de um timeout ou da recepcao de uma mensagem STOP
    private void evaluateStops(int nextTS) {

        ObjectOutputStream out = null;
        ByteArrayOutputStream bos = null;

        lcManager.nexttsLock();
        lcManager.lasttsLock();
        lcManager.StopsLock();

        // passar para a fase de troca de lider se já tiver recebido mais de f mensagens
        if (lcManager.getStopsSize(nextTS) > this.reconfManager.getQuorumF() && lcManager.getNextts() == lcManager.getLastts()) {

            lcManager.setNextts(lcManager.getLastts() + 1); // definir proximo timestamp

            int ts = lcManager.getNextts();

            // guardar informacao da mensagem que vou enviar
            lcManager.addStop(ts, this.reconfManager.getStaticConf().getProcessId());

            execManager.stop(); // parar execucao do consenso

            try { // serializar conteudo a enviar na mensagem STOP
                bos = new ByteArrayOutputStream();
                out = new ObjectOutputStream(bos);

                if (lcManager.getCurrentRequestTimedOut() != null) {

                    //TODO: Se isto estiver a null, e porque nao houve timeout. Fazer o q?
                    out.writeBoolean(true);
                    byte[] msgs = bb.makeBatch(lcManager.getCurrentRequestTimedOut(), 0, 0, reconfManager);
                    out.writeObject(msgs);
                }
                else {
                    out.writeBoolean(false);
                }

                out.flush();
                bos.flush();

                byte[] payload = bos.toByteArray();
                out.close();
                bos.close();

                // enviar mensagem STOP
                communication.send(this.reconfManager.getCurrentViewOtherAcceptors(),
                    new LCMessage(this.reconfManager.getStaticConf().getProcessId(), TOMUtil.STOP, ts, payload));

            } catch (IOException ex) {
                java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    out.close();
                    bos.close();
                } catch (IOException ex) {
                    java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }

        // posso passar para a fase de sincronizacao?
        if (lcManager.getStopsSize(nextTS) > this.reconfManager.getQuorum2F() && lcManager.getNextts() > lcManager.getLastts()) {

            lcManager.setLastts(lcManager.getNextts()); // definir ultimo timestamp

            lcManager.nexttsUnlock();

            int ts = lcManager.getLastts();
            lcManager.lasttsUnlock();

            // evitar um memory leak
            lcManager.removeStops(nextTS);

            lcManager.StopsUnlock();

            int leader = ts % this.reconfManager.getCurrentViewN(); // novo lider
            int in = getInExec(); // eid a executar
            int last = getLastExec(); // ultimo eid decidido

            // Se eu nao for o lider, tenho que enviar uma mensagem SYNC para ele
            if (leader != this.reconfManager.getStaticConf().getProcessId()) {

                try { // serializar o conteudo da mensagem SYNC

                    bos = new ByteArrayOutputStream();
                    out = new ObjectOutputStream(bos);

                    if (last > -1) { // conteudo do ultimo eid decidido

                        out.writeBoolean(true);
                        out.writeInt(last);
                        Execution exec = execManager.getExecution(last);
                        byte[] decision = exec.getLearner().getDecision();

                        out.writeObject(decision);

                        // TODO: VAI SER PRECISO METER UMA PROVA!!!

                    }

                    else out.writeBoolean(false);

                    if (in > -1) { // conteudo do eid a executar

                        Execution exec = execManager.getExecution(in);

                        RoundValuePair quorumWeaks = exec.getQuorumWeaks();
                        HashSet<RoundValuePair> writeSet = exec.getWriteSet();

                        CollectData collect = new CollectData(this.reconfManager.getStaticConf().getProcessId(), in, quorumWeaks, writeSet);

                        SignedObject signedCollect = sign(collect);

                        out.writeObject(signedCollect);

                        //out.writeInt(in);
                        //out.writeObject(exec.getQuorumWeaks());
                        //out.writeObject(exec.getWriteSet());
                    }

                    else {

                        CollectData collect = new CollectData(this.reconfManager.getStaticConf().getProcessId(), -1, new RoundValuePair(-1, new byte[0]), new HashSet<RoundValuePair>());

                        SignedObject signedCollect = sign(collect);

                        out.writeObject(signedCollect);

                    }

                    out.flush();
                    bos.flush();

                    byte[] payload = bos.toByteArray();
                    out.close();
                    bos.close();

                    leaderLock.lock();
                    lm.setNewTS(ts);
                    leaderLock.unlock();

                    int[] b = new int[1];
                    b[0] = leader;

                    // enviar mensagem SYNC para o novo lider
                    communication.send(b,
                        new LCMessage(this.reconfManager.getStaticConf().getProcessId(), TOMUtil.SYNC, ts, payload));

                    //TODO: Voltar a ligar o timeout

                } catch (IOException ex) {
                    java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                } finally {
                    try {
                        out.close();
                        bos.close();
                    } catch (IOException ex) {
                        java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }

            } else { // se for o lider, vou guardar a informacao que enviaria na mensagem SYNC

                LastEidData lastData = null;
                CollectData collect = null;

                if (last > -1) {  // conteudo do ultimo eid decidido
                    Execution exec = execManager.getExecution(last);
                    byte[] decision = exec.getLearner().getDecision();

                    lastData = new LastEidData(this.reconfManager.getStaticConf().getProcessId(), last, decision, null);
                    // TODO: VAI SER PRECISO METER UMA PROVA!!!

                }
                else lastData = new LastEidData(this.reconfManager.getStaticConf().getProcessId(), last, null, null);

                lcManager.addLastEid(ts, lastData);


                if (in > -1) { // conteudo do eid a executar
                    Execution exec = execManager.getExecution(in);

                    RoundValuePair quorumWeaks = exec.getQuorumWeaks();
                    HashSet<RoundValuePair> writeSet = exec.getWriteSet();

                    collect = new CollectData(this.reconfManager.getStaticConf().getProcessId(), in, quorumWeaks, writeSet);

                }
                else collect = new CollectData(this.reconfManager.getStaticConf().getProcessId(), -1, new RoundValuePair(-1, new byte[0]), new HashSet<RoundValuePair>());

                SignedObject signedCollect = sign(collect);

                lcManager.addCollect(ts, signedCollect);
            }

        }
        else {
            lcManager.StopsUnlock();
            lcManager.nexttsUnlock();
            lcManager.lasttsUnlock();
        }
    }

    /**
     * Este metodo e invocado pelo MessageHandler sempre que receber mensagens relacionados
     * com a troca de lider
     * @param msg Mensagem recebida de outra replica
     */
    public void deliverTimeoutRequest(LCMessage msg) {

        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;

        switch (msg.getType()) {
            case TOMUtil.STOP: // mensagens STOP

                {
                    lcManager.lasttsLock();

                    // esta mensagem e para a proxima mudanca de lider?
                    if (msg.getTs() == lcManager.getLastts() + 1) {

                        lcManager.lasttsUnlock();

                        try { // descerializar o conteudo da mensagem STOP

                            bis = new ByteArrayInputStream(msg.getPayload());
                            ois = new ObjectInputStream(bis);

                            boolean hasReqs = ois.readBoolean();
                            clientsManager.getClientsLock().lock();

                            if (hasReqs) {

                                // Guardar os pedidos que a outra replica nao conseguiu ordenar
                                //TODO: Os requests  tem q ser verificados!
                                byte[] temp = (byte[]) ois.readObject();
                                BatchReader batchReader = new BatchReader(temp,
                                        reconfManager.getStaticConf().getUseSignatures() == 1);
                                TOMMessage[] requests = batchReader.deserialiseRequests(reconfManager);
                            }
                            clientsManager.getClientsLock().unlock();

                            ois.close();
                            bis.close();

                        } catch (IOException ex) {
                            java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                        } catch (ClassNotFoundException ex) {
                            java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);

                        }

                        // guardar informacao sobre a mensagem STOP
                        lcManager.StopsLock();
                        lcManager.addStop(msg.getTs(), msg.getSender());
                        lcManager.StopsUnlock();

                        evaluateStops(msg.getTs()); // avaliar mensagens stops
                    }
                    else {
                        lcManager.lasttsUnlock();
                    }
                }
                break;
            case TOMUtil.SYNC: // mensagens SYNC
                {

                    int ts = msg.getTs();

                    lcManager.lasttsLock();

                    // Sou o novo lider e estou a espera destas mensagem?
                    if (ts == lcManager.getLastts() &&
                            this.reconfManager.getStaticConf().getProcessId() == (ts % this.reconfManager.getCurrentViewN())) {

                        //TODO: E preciso verificar a prova do ultimo consenso decidido e a assinatura do estado do consenso actual!

                        lcManager.lasttsUnlock();

                        LastEidData lastData = null;
                        SignedObject signedCollect = null;

                        int last = -1;
                        byte[] lastValue = null;

                        int in = -1;

                        RoundValuePair quorumWeaks = null;
                        HashSet<RoundValuePair> writeSet = null;


                        try { // descerializar o conteudo da mensagem

                            bis = new ByteArrayInputStream(msg.getPayload());
                            ois = new ObjectInputStream(bis);

                            if (ois.readBoolean()) { // conteudo do ultimo eid decidido


                                last = ois.readInt();

                                lastValue = (byte[]) ois.readObject();

                                //TODO: Falta a prova!

                            }

                            lastData = new LastEidData(msg.getSender(), last, lastValue, null);

                            lcManager.addLastEid(ts, lastData);

                            // conteudo do eid a executar

                            signedCollect = (SignedObject) ois.readObject();

                            /*in = ois.readInt();
                            quorumWeaks = (RoundValuePair) ois.readObject();
                            writeSet = (HashSet<RoundValuePair>) ois.readObject();*/



                            /*collect = new CollectData(msg.getSender(), in,
                                    quorumWeaks, writeSet);*/

                            ois.close();
                            bis.close();

                            lcManager.addCollect(ts, signedCollect);

                            int bizantineQuorum = (reconfManager.getCurrentViewN() + reconfManager.getCurrentViewF()) / 2;

                            // ja recebi mensagens de um quorum bizantino,
                            // referentes tanto ao ultimo eid como o actual?s
                            if (lcManager.getLastEidsSize(ts) > bizantineQuorum &&
                                    lcManager.getCollectsSize(ts) > bizantineQuorum) {

                                catch_up(ts);
                            }

                        } catch (IOException ex) {
                            java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                        } catch (ClassNotFoundException ex) {
                            java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);

                        }

                  }
            }
            break;
        case TOMUtil.CATCH_UP: // mensagens de CATCH-UP
            {
                int ts = msg.getTs();

                lcManager.lasttsLock();

                // Estou a espera desta mensagem, e recebi-a do novo lider?
                if (msg.getTs() == lcManager.getLastts() && msg.getSender() == (ts % this.reconfManager.getCurrentViewN())) {

                    lcManager.lasttsUnlock();

                    LastEidData lastHighestEid = null;
                    int currentEid = -1;
                    HashSet<SignedObject> signedCollects = null;
                    byte[] propose = null;
                    int batchSize = -1;

                    try { // descerializar o conteudo da mensagem

                        bis = new ByteArrayInputStream(msg.getPayload());
                        ois = new ObjectInputStream(bis);

                        lastHighestEid = (LastEidData) ois.readObject();
                        currentEid = ois.readInt();
                        signedCollects = (HashSet<SignedObject>) ois.readObject();
                        propose = (byte[]) ois.readObject();
                        batchSize = ois.readInt();

                        lcManager.setCollects(ts, signedCollects);

                        // o predicado sound e verdadeiro?
                        if (lcManager.sound(lcManager.selectCollects(ts, currentEid))) {

                            finalise(ts, lastHighestEid, currentEid, signedCollects, propose, batchSize, false);
                        }

                        ois.close();
                        bis.close();

                    } catch (IOException ex) {
                        java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (ClassNotFoundException ex) {
                        java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);

                    }

                }
                else {
                    lcManager.lasttsUnlock();
                }
            }
            break;

        }

    }

    // este metodo e usado para verificar se o lider pode fazer a mensagem catch-up
    // e tambem envia-la
    private void catch_up(int ts) {

        ObjectOutputStream out = null;
        ByteArrayOutputStream bos = null;

        LastEidData lastHighestEid = lcManager.getHighestLastEid(ts);

        int currentEid = lastHighestEid.getEid() + 1;
        HashSet<SignedObject> signedCollects = null;
        byte[] propose = null;
        int batchSize = -1;

        // normalizar os collects e aplicar-lhes o predicado "sound"
        if (lcManager.sound(lcManager.selectCollects(ts, currentEid))) {

            signedCollects = lcManager.getCollects(ts); // todos collects originais que esta replica recebeu

            Consensus cons = new Consensus(-1); // este objecto só serve para obter o batchsize,
                                                    // a partir do codigo que esta dentro do createPropose()

            propose = createPropose(cons);
            batchSize = cons.batchSize;

            try { // serializar a mensagem CATCH-UP
                bos = new ByteArrayOutputStream();
                out = new ObjectOutputStream(bos);

                out.writeObject(lastHighestEid);

                //TODO: Falta serializar a prova!!

                out.writeInt(currentEid);
                out.writeObject(signedCollects);
                out.writeObject(propose);
                out.writeInt(batchSize);

                out.flush();
                bos.flush();

                byte[] payload = bos.toByteArray();
                out.close();
                bos.close();

                // enviar a mensagem CATCH-UP
                communication.send(this.reconfManager.getCurrentViewOtherAcceptors(),
                    new LCMessage(this.reconfManager.getStaticConf().getProcessId(), TOMUtil.CATCH_UP, ts, payload));

                finalise(ts, lastHighestEid, currentEid, signedCollects, propose, batchSize, true);

            } catch (IOException ex) {
                java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    out.close();
                    bos.close();
                } catch (IOException ex) {
                    java.util.logging.Logger.getLogger(TOMLayer.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    // este metdo e invocado em todas as replicas, e serve para verificar e aplicar
    // a informacao enviada na mensagem catch-up
    private void finalise(int ts, LastEidData lastHighestEid,
            int currentEid, HashSet<SignedObject> signedCollects, byte[] propose, int batchSize, boolean iAmLeader) {

        int me = this.reconfManager.getStaticConf().getProcessId();
        Execution exec = null;
        Round r = null;

        // Esta replica esta atrasada?
        if (getLastExec() + 1 < lastHighestEid.getEid()) {
            //TODO: Caso em que e necessario aplicar a transferencia de estado


        } else if (getLastExec() + 1 == lastHighestEid.getEid()) {
        // esta replica ainda esta a executar o ultimo consenso decidido?

            //TODO: e preciso verificar a prova!

            exec = execManager.getExecution(lastHighestEid.getEid());
            r = exec.getLastRound();

            if (r == null) {
                exec.createRound(reconfManager);
            }

            byte[] hash = computeHash(propose);
            r.propValueHash = hash;
            r.propValue = propose;
            r.deserializedPropValue = checkProposedValue(propose);
            r.setDecide(me, hash);
            exec.decided(r, hash); // entregar a decisao a delivery thread
        }
        byte[] tmpval = null;

        HashSet<CollectData> selectedColls = lcManager.selectCollects(signedCollects, currentEid);

        // obter um valor que satisfaca o predicado "bind"
        tmpval = lcManager.getBindValue(selectedColls);

        // se tal valor nao existir, obter o valor escrito pelo novo lider
        if (tmpval == null && lcManager.unbound(selectedColls)) {
            tmpval = propose;
        }

        if (tmpval != null) { // consegui chegar a algum valor?

            exec = execManager.getExecution(currentEid);
            exec.incEts();

            exec.removeWritten(tmpval);
            exec.addWritten(tmpval);

            r = exec.getLastRound();

            if (r == null) {
                r = exec.createRound(reconfManager);
            }
            else {
                r.clear();
            }

            byte[] hash = computeHash(tmpval);
            r.propValueHash = hash;
            r.propValue = tmpval;
            r.deserializedPropValue = checkProposedValue(tmpval);

            if(exec.getLearner().firstMessageProposed == null)
                exec.getLearner().firstMessageProposed = r.deserializedPropValue[0];
            
            r.setWeak(me, hash);

            lm.setNewTS(ts);

            // resumir a execucao normal
            execManager.restart();
            leaderChanged = true;
            setInExec(currentEid);
            if (iAmLeader) {
                imAmTheLeader();
            } // acordar a thread que propoem valores na operacao normal

            // enviar mensagens WEAK para as outras replicas
            communication.send(this.reconfManager.getCurrentViewOtherAcceptors(),
                    acceptor.getFactory().createWeak(currentEid, r.getNumber(), r.propValueHash));

        }

    }
    /**************************************************************/
}
