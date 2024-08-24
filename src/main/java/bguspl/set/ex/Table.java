package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)


    protected final boolean[][] slotToToken ;

    // this method put id of player in slot


    protected final int[] playerTokenCounter ;

    protected final int maxTokens=3;





    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot,boolean[][] slotToToken, int[] playerTokenCounter) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.slotToToken=slotToToken;
        this.playerTokenCounter=playerTokenCounter;
    }


    public Table(Env env,Integer[] slotToCard,Integer[] cardToSlot) {

        this(env, 
        slotToCard,
        cardToSlot,
        new boolean[env.config.tableSize][env.config.players], 
        new int[env.config.players]);
    }
    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env){
        this(env,
         new Integer[env.config.tableSize],
         new Integer[env.config.deckSize],
         new boolean[env.config.tableSize][env.config.players],
                new int[env.config.players]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public synchronized void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public synchronized int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
       
        synchronized(this){
            cardToSlot[card] = slot;
            slotToCard[slot] = card;
            env.ui.placeCard(card,slot);

        }
    
    }

    public synchronized int placeCard(int card){
        int[] emptySlots= IntStream.range(0,slotToCard.length).filter(i->slotToCard[i]==null).toArray();
        if(emptySlots.length==0)
               return -1;   // no available slots!
        int randomSlotIndex= ThreadLocalRandom.current().nextInt(emptySlots.length);
        int selectedSlot=emptySlots[randomSlotIndex];
        placeCard(card,selectedSlot);
        return selectedSlot;

    }



    public synchronized int getPlayerCounter(int player){
        return playerTokenCounter[player];


    }
    public synchronized int emptySlotCounter(){
        return slotToCard.length-countCards();

    }


    public void removeCardById(int card){
        removeCard(cardToSlot[card]);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {

        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        synchronized (this){
            int card=slotToCard[slot];
            slotToCard[slot]=null;
            cardToSlot[card]=null;
            removeTokens(slot);
            env.ui.removeCard(slot);
        }

        
    }

    public synchronized void removeCardSafe(int slot){
        if(slotToCard[slot]!=null)
               removeCard(slot);
    }

    public synchronized void clearTable(){
        List<Integer> slotIndexs= IntStream.range(0,slotToCard.length).boxed()
                .collect(Collectors.toList());
        Collections.shuffle(slotIndexs);
        slotIndexs.forEach(this::removeCardSafe);
    }


    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized boolean placeToken(int player, int slot) {
       
       if(slotToToken[slot][player] || playerTokenCounter[player]>=maxTokens)
               return false;

       slotToToken[slot][player]=true;
       playerTokenCounter[player]++;
       env.ui.placeToken(player,slot);
       return true;

    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {
       if(slotToToken[slot][player]==false){
           return false;
       }
       slotToToken[slot][player]=false;
       playerTokenCounter[player]--;
       env.ui.removeToken(player,slot);
       return true ;

    }
    public synchronized void removeTokens(int slot){
        for (int i=0;i<slotToToken[slot].length;i++){
            removeToken(i,slot);
        }
    }

    public synchronized List<Integer> cardsOfPlayerTokens(int player){

        List<Integer> cards=new ArrayList<>(maxTokens);
        IntStream.range(0,slotToToken.length).filter(i->slotToToken[i][player])
                .forEach(i->{
                    Integer card=slotToCard[i];
                    if(card!=null)
                          cards.add(card);
                });

        return cards;

    }


    // this method is public method hte update the token state for a given player in a specific slot
    // we use synchronized to ensure that the method is thread safe which mean multiple threads can try
    // execute it simultaniously for the same obkect , they will be
    public synchronized boolean updatePlayerToken(int player,int slot){
        if (isSlotEmpty(slot)) {  // make shore i don't update in empty slot
            return false;
        }
        return toggleTokenState(player,slot);

    }


    private synchronized boolean isSlotEmpty(int slot){
        return slotToCard[slot]==null;
    }



    private synchronized boolean  toggleTokenState(int player,int slot){
        boolean tokenExists=slotToToken[slot][player];
        return tokenExists? removeToken(player,slot):placeToken(player,slot);

    }
}
