package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    protected Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private volatile boolean terminateAI;

    /**
     * The current score of the player.
     */

    private final Dealer myDealer;


    private final BlockingQueue<Integer> storeActions;

    private final long updateFreeze;


    private int counter=0;

    private int score;


    private long freezeTime=Long.MIN_VALUE;


    private static final long DISPLAY_OFFSET_MILLIS=999;
    private static final long MIN_DISPLAY_TIME=1000;



    private final long MAX_SLEEP_TIME_MS = 100;
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param mYDealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer mYDealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.myDealer=mYDealer;
        this.human = human;
        this.storeActions=new ArrayBlockingQueue<>(env.config.featureSize);
        this.updateFreeze= Math.min(MAX_SLEEP_TIME_MS, Math.min(env.config.penaltyFreezeMillis, env.config.pointFreezeMillis));
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try{
                if(Frozen())
                    updateFreezingTime();
                int slot=storeActions.take();
                if(!myDealer.isReshuffle()){
                    env.logger.info("Processing key for player "+id+" on slot: "+slot);
                    if(table.updatePlayerToken(id,slot) && table.getPlayerCounter(id)==table.maxTokens)
                        checkMySet();
                }
            }catch (InterruptedException e){
                env.logger.info("Player "+id+" thread got interrupted");
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        env.logger.info( "Player Id " + id + " set counter is: " + counter);
    }
     public int getId(){
        return id;

     }
    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {

        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminateAI) {
                try {
                    int simulatedAction= ThreadLocalRandom.current().nextInt(env.config.tableSize);
                    storeActions.put(simulatedAction);
                } catch (InterruptedException ignored) {}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);

        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        env.logger.info("Terminate was called on "+id );
        if(!human){
            terminateAI=true;
            env.logger.info("Interrupting player "+id+"ai thread");
            aiThread.interrupt();
            try{aiThread.join();}catch (InterruptedException ignored){}

        }
        terminate=true;
        env.logger.info("Interrupting player "+id+" thread");
        playerThread.interrupt();

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
       if(!storeActions.offer(slot)){
           env.logger.info("failed to add keyPress to queue");
       }
    }
    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {

      // this part is just for demonstration in the unit tests
        int ignored=table.countCards();
        env.ui.setScore(id, ++score);
        freezeTime=(System.currentTimeMillis()+env.config.pointFreezeMillis);

    }

     public void updateFreezingTime(){
        try{
            while(Frozen()){
                long remainingFreezeMillis=calculateRemainingFreezeMillis();
                displayFreezeTime(remainingFreezeMillis);
                safelySleep(updateFreeze);
            }
            resetFreezeDisplay();
        }catch (InterruptedException e){
                handleInteruptDuringFreeze();
        }finally {
            clearIncomingActions();
        }

     }

     private long calculateRemainingFreezeMillis(){
        long remainingTime=freezeTime-System.currentTimeMillis();
        return remainingTime>0? remainingTime+DISPLAY_OFFSET_MILLIS:MIN_DISPLAY_TIME;

     }
     private void displayFreezeTime(long remainingFreeze){
        env.ui.setFreeze(id,remainingFreeze);
    }
    private void safelySleep(long millis) throws InterruptedException{
        Thread.sleep(millis);
    }
    private void resetFreezeDisplay(){
        env.ui.setFreeze(id,0);
    }
    private void clearIncomingActions(){
        storeActions.clear();
    }
    private void handleInteruptDuringFreeze(){
        Thread.currentThread().interrupt();
        env.logger.info("Player "+id+" was interuptted durring freeze preiod");
    }


    private void checkMySet(){
      try{
          synchronized (this){
              env.logger.info("Player is waiting to add to check queue");
              myDealer.check.put(id);
              env.logger.info("Player "+id+" addef to check queue");
              myDealer.dealerThread.interrupt();
              counter++;

              while(myDealer.check.contains(id)){
                  env.logger.info("Player is waiting to deealer to check his set ! ");
                  wait();

              }
              env.logger.info("Player finished waiting to dealer to check his set! ");
          }
      }catch (InterruptedException e){

          env.logger.info("Player "+id+" thread was interrupted while waiting for the check");
      }
    }


    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
       freezeTime=System.currentTimeMillis()+env.config.penaltyFreezeMillis;

    }

    public synchronized boolean Frozen(){
        return freezeTime>System.currentTimeMillis();

    }

    public int score() {

        return score;
    }
}
