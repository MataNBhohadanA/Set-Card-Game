package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;


    private static final int sleepTime=100; // constant defining the sleep time in milliesecond usedsin varirous waiting ops


    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;  // this list representing the deck of the card ids avialable for play


    /**
     * True iff game should be terminated.
     */


    /**
     * the list of the cards still on the table
     *
     */
    private final List<Integer> onTable;  // a list of the cards  that are currently on the table

 

    protected final BlockingQueue<Integer> check;  // a blocking queue for coordination actions to check sets .

    protected Thread dealerThread;  // the thread on which the dealer's main loop runs


    private volatile boolean terminate;  // this flag indicating wether the game sohuld  be terminated !

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;   // the time at which the dealer needs to reshuffle the deck due to turn timeout .

    private volatile boolean isReshuffle ;   // a flag indicationg whether the dealer is in reshulling state ;


    public Dealer(Env env, Table table, Player[] players) {   // constructor
        this.env = env;
        this.table = table;
        this.players = players;
        check=new ArrayBlockingQueue<>(1,true);
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        onTable=new ArrayList<>(env.config.tableSize);

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */

    // The main loop of the dealer thread , responsible of running player threads
    // managing the game flow - including placing cards on the table , checking for a game termination , updating the timer display and clearing the talbe and
    // announcing winners

    @Override
    public void run() {
        dealerThread=Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        isReshuffle=true;
        runPlayerThreads();
        while (!shouldFinish()) {
            placeCardsOnTable();
            isReshuffle=false;
            timerLoop();
            isReshuffle=true;
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminateAll();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void runPlayerThreads(){
        for(Player player:players){
            Thread playerThread=new Thread(player,"Player "+player.getId());
            playerThread.start();
        }
    }

    
      private void timerLoop() {
        boolean cardsRemoved = true;
        
        while (!terminate && (env.config.turnTimeoutMillis <= 0 || System.currentTimeMillis() < reshuffleTime)) {
            // if cards were removed make sure there are still sets available in the game/on the table
            if (cardsRemoved && (
                    (env.config.turnTimeoutMillis > 0 && env.util.findSets(Stream.concat(deck.stream(),
                            onTable.stream()).collect(Collectors.toList()), 1).size() == 0)
                            || (env.config.turnTimeoutMillis <=0 && env.util.findSets(onTable, 1).size() == 0))) {
                break;
            }
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            cardsRemoved = removeCardsFromTable();
            placeCardsOnTable();
        }
    }


    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        synchronized (this){
            terminate=true;
            this.notifyAll();
        }

    }

    public void terminateAll(){
        for (int i=players.length-1;i>=0;i--)
        {
            terminateSinglePlayer(players[i]);
        }
    }
    private void terminateSinglePlayer(Player player ){
        logTerminationStart(player);
        player.terminate();
        waitForPlayerThreadToTerminate(player);

    }
    private void logTerminationStart(Player player){
        env.logger.info("Initiating termination for player"+player.id);
    }
    private void waitForPlayerThreadToTerminate(Player player){
        try{
            player.playerThread.join();
            env.logger.info("Player "+player.getId()+"  thread terminated ");

        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            env.logger.info("Dealer thread interuppeted while waiting for player "+player.getId()+" to terminate ");
        }
    }
    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    

     private boolean removeCardsFromTable(){
        boolean cardsRemoved=false;
        Integer playerId=check.peek();
        if(playerId==null){
            return false;
        }
        env.logger.info("initiating set check fo r player"+playerId);
        
        synchronized(players[playerId]){
            List<Integer> selectedCards=table.cardsOfPlayerTokens(playerId);
            if(validateSetSelection(selectedCards, playerId)){
                cardsRemoved=evaluateSelectedSet(selectedCards, playerId);

            }else{
                cardsRemoved=false;
            }


            check.remove();
            players[playerId].notify();
            env.logger.info("Completed set check for player ! successfully! ");
                
        }
        return cardsRemoved;
     }


    private  boolean validateSetSelection(List<Integer> selectedCards,Integer playerId){
        if(selectedCards.size()!=table.maxTokens){
            env.logger.info("Player"+ playerId+" atttempted a set check with incorrect number of cards");
            return false;
        }
        return true;
    }

    private boolean evaluateSelectedSet(List<Integer> selectedCards,Integer playerId){

        boolean cardsRemoved=false;
        if(env.util.testSet(selectedCards.stream().mapToInt(i->i).toArray())){
            selectedCards.forEach(card->{
                table.removeCardById(card);
                env.logger.info("valid set removed for player "+playerId);
            });
            players[playerId].point();
            cardsRemoved=true;

        }else{
            env.logger.info("Invalid set for player" +playerId);
            players[playerId].penalty();
            cardsRemoved=false;

        }

        return cardsRemoved;
    }




    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
       if(deck.isEmpty())
           return;

       int emptySlots=table.emptySlotCounter();
       if(emptySlots<=0){
           env.logger.info("No empty slots to place cards");
           return;
       }

       Collections.shuffle(deck);
       IntStream.range(0,emptySlots).forEach(i->{
           if(!deck.isEmpty()){
               int card=deck.remove(0);
               int slotPlaced=table.placeCard(card);
               if(slotPlaced!=-1){
                   env.logger.info("Placed card "+card+" in slot "+slotPlaced);
               }else{
                   env.logger.info("Failed to place card "+card+" unexpected full table");

               }


           }
       });
       updateTimerDisplay(true);
       if(env.config.hints){
           env.logger.info("Dealer reshuffled.Available hints : ");
           table.hints();
       }



    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if(terminate)
            return;
        try{
            if(env.config.turnTimeoutMillis>=0 && check.isEmpty()){
                Thread.sleep(sleepTime);

            }else{
               synchronized (this){
                   while(!terminate && check.isEmpty()){
                       wait();
                   }
               }
            }
        }catch (InterruptedException e){
            env.logger.info("Dealer thread was interrupted! ");
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis;
        }
        if(env.config.turnTimeoutMillis>0){
            long timeRemaining=reshuffleTime-System.currentTimeMillis();
            boolean showWarning=timeRemaining<env.config.turnTimeoutWarningMillis;
            env.ui.setCountdown(Math.max(timeRemaining,0),showWarning);

        }
        else if(env.config.turnTimeoutMillis==0){
            long elapsedTimeSinceLastAction=System.currentTimeMillis()-reshuffleTime;
            env.ui.setElapsed(elapsedTimeSinceLastAction);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
      table.clearTable();
      deck.addAll(onTable);
      onTable.clear();


    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
       final int maxScore= Arrays.stream(players).mapToInt(Player::score).max().orElse(0);
       int[] winnerIds=Arrays.stream(players).filter(player->player.score()==maxScore).mapToInt(Player::getId).toArray();
       env.ui.announceWinner(winnerIds);
       env.logger.info("Annoncing winners with score: "+maxScore+". Winners: "+Arrays.toString(winnerIds));
    }

    public boolean isReshuffle(){
        return isReshuffle;
    }
}
