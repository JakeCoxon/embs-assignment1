package embs;

import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;

public class Source {
  
  // TODO: Sync phase can get first beacon of mote0, first beacon of mote1,
  // then second beacon of mote0, second beacon of mote2

  private static final int MOTE0 = 0;
  private static final int MOTE1 = 1;
  private static final int MOTE2 = 2;

  /* Sync states */
  /** The program should wait for 2 beacons to calculate the next 
   *  synchronization stage */
  private static final int S_NORMAL = 0;
  /** The program should wait for 2 beacons but the first beacon
   *  it receives should carry the highest n value for the mote. */
  private static final int S_FINDN = 1;
  /** The program should not wait for any beacons for this mote */
  private static final int S_NONE = 2;

  private static final int SYNC_PHASE = 0x10;
  private static final int RECEP_PHASE = 0x20;
  

  private static final long MAX_BEACON_TICKS = Time.toTickSpan(Time.MILLISECS, 1500);
  private static final long MIN_BEACON_TICKS = Time.toTickSpan(Time.MILLISECS, 500);
  private static final long PADDING_TICKS =    Time.toTickSpan(Time.MILLISECS, 50);

  private static final byte[] xmit;
  private static final byte my_address = 0x10;

  private static final Radio radio = new Radio();
  
  /* Timer per mote */
  private static final Timer tsend0 = new Timer();
  private static final Timer tsend1 = new Timer();
  private static final Timer tsend2 = new Timer();
  
  @Immutable
  private static final byte[] CHANNELS =      new byte[] {0, 1, 2};
  @Immutable
  private static final byte[] PANIDS =        new byte[] {0x11, 0x12, 0x13};
  
  /** The known t (delta) value for each mote **/
  private static final long[] TIMES =         new long[] {-1, -1, -1};
  /** The timings of each timer **/
  private static final long[] TIMER_TIMES =   new long[] {-1, -1, -1};
  /** The n value of each mote **/
  private static final  int[] NS =            new int[] {1, 1, 1};
  /** The synchronization state of each mote **/
  private static final  int[] SYNC_STATES =   new int[] {S_NORMAL, S_NORMAL, S_NORMAL};
  /** The absolute time of the previous beacon **/
  private static final long[] PREV_TIMES =    new long[] {-1, -1, -1};
  /** The n value for the previous beacon **/
  private static final  int[] PREV_N =        new int[] {-1,-1, -1};
  /** Whether a mote wants to be synced **/
  private static final  int[] QUEUE =         new int[] {1, 1, 1};
  
  /** The id of the current mote that is syncing **/
  private static  int sync_id = -1;
  
  /**
   * We need a way of telling whether the radio switched off because
   * we told it to, or the radio timed out, the easiest way is to set
   * this to true every time we turn the radio off. It will be set back
   * to false we receive the off notice.
   */
  private static boolean manual_off = false;

  static {

    // Prepare data frame
    xmit = new byte[12];
    xmit[0] = Radio.FCF_DATA;
    xmit[1] = Radio.FCA_SRC_SADDR | Radio.FCA_DST_SADDR;
    Util.set16le(xmit, 5, 0xFFFF); // broadcast address 
    Util.set16le(xmit, 9, 0x10);   // own short address
    Util.set16le(xmit, 3, 0x0);    // destination PAN address 
    Util.set16le(xmit, 7, 0x0);    // own PAN address 
    
    
    tsend0.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time); }
    });
    tsend1.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time); }
    });
    tsend2.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time); }
    });
    
    // Radio handlers
    radio.setRxHandler(new DevCallback(null){
      public int invoke (int flags, byte[] data, int len, int info, long time) {
        return Source.onReceive(flags, data, len, info, time); }
    });
    radio.setTxHandler(new DevCallback(null) {
      public int invoke(int flags, byte[] data, int len, int info, long time) {
        return Source.onSent(flags, data, len, info, time); }
    });

    // Open the default radio
    radio.open(Radio.DID, null, 0, 0);
    radio.setShortAddr(my_address);
    
    setRadioForSync(MOTE0);
      
  }
  
  /**
   * @return Whether there is a sync currently happening
   */
  private static boolean shouldWaitForSync() {
    return sync_id != -1;
  }

  /**
   * Stops the radio at sets {@link #manual_off} to true
   * @see #manual_off
   */
  private static void stopRadioManually() {
    manual_off = true;
    radio.stopRx();
  }

  /**
   * Records the state of the first beacon
   * @param mote_id
   * @param number the received n value
   * @param time the time the beacon was received
   */
  private static void firstBeacon(int mote_id, int number, long time) {
    log_firstBeacon(mote_id, number);

    /*
     * If first beacon has n=1 then we know there is no more syncs but we don't know t
     */
    if (number == 1) {

      if (SYNC_STATES[mote_id] == S_NORMAL) {
        // Set the state to get n_max next time we receive a beacon
        SYNC_STATES[mote_id] = S_FINDN;
        Logger.appendString(csr.s2b("Find n for mote"));
        Logger.appendInt(mote_id);
        Logger.flush(Mote.WARN);
      }
      
      // There's no point trying to sync again until it has completed a new cycle
      
      long min_sync_time = time + 11 * MIN_BEACON_TICKS;
      startTimer(mote_id, SYNC_PHASE, min_sync_time, -PADDING_TICKS);
      
      QUEUE[mote_id] = 0; // UNQUEUE

      pickNextSync(mote_id, false);
    }
    
    PREV_TIMES[mote_id] = time;
    PREV_N[mote_id] = number;
  }
  
  /**
   * Records the state of the second beacon
   * @param mote_id
   * @param number the received n value
   * @param time the time the beacon was received
   */
  private static void secondBeacon(int mote_id, int number, long time) {

    log_secondBeacon(mote_id, number);
    
    
    long time_delta = time - PREV_TIMES[mote_id];
    
    /* 
     * Divide this up by the difference in n. Usually this will just
     * be 1 but there's a chance we missed a beacon (due to transmitting
     * a packet) or we are doing a FINDN across 2 cycles and should divide
     * accordingly
     */
    int n_delta = PREV_N[mote_id] - number;
    if (SYNC_STATES[mote_id] == S_FINDN)
      n_delta = PREV_N[mote_id] + 11;
    
    time_delta /= n_delta;
    
    TIMES[mote_id] = time_delta;
    QUEUE[mote_id] = 0;
    

    // Completed 1 normal sync, so next time try and find n
    if (SYNC_STATES[mote_id] == S_NORMAL) {
      SYNC_STATES[mote_id] = S_FINDN;

      Logger.appendString(csr.s2b("Find n for mote"));
      Logger.appendInt(mote_id);
      Logger.flush(Mote.WARN);
    }
    
    /*
     * Record time difference (t) and set up the next sync time for 
     * this mote. Start the timer for reception phase in n*t time
     * but add 1% of t to make sure we are actually in the time frame.
     * (Sometimes the timer starts 0.006ms before the time frame)
     */
    long reception_time = time + number * time_delta;
    
    log_timeDiff(mote_id, time_delta, PREV_TIMES[mote_id], n_delta);
    log_startRecepTimer(mote_id, reception_time);
    
    startTimer(mote_id, RECEP_PHASE, reception_time, PADDING_TICKS);

    PREV_TIMES[mote_id] = time;
    PREV_N[mote_id] = number;
    
    pickNextSync(mote_id, false);
  }
  
  /**
   * Picks another mote to sync or if there is none queued then just stop
   * @param mote_id The currently syncing mote
   * @param timedout Whether this call is because of a timeout, if this is the
   * case then retrying the same mote is preferred
   */
  private static void pickNextSync(int mote_id, boolean timedout) {

    if (radio.getState() != Radio.S_STDBY)
      Source.stopRadioManually();
    
    long time = Time.currentTicks();
    
    /*
     *  Pick a mote that needs syncing:
     *  We should try this mote again is there is a possibility that we
     *  could receive the second sync beacon
     *  
     *  Otherwise pick another mote that needs to be synced, with this
     *  mote as a last resort.
     */
    boolean retry = timedout && PREV_N[mote_id] != 1
                             && PREV_TIMES[mote_id] != -1
                             && time - PREV_TIMES[mote_id] < MAX_BEACON_TICKS;
    

    int next_id = retry ? mote_id :
        QUEUE[(mote_id + 2) % 3] == 1 ? (mote_id + 2) % 3 :
        QUEUE[(mote_id + 1) % 3] == 1 ? (mote_id + 1) % 3 :
        QUEUE[(mote_id + 0) % 3] == 1 ? (mote_id + 0) % 3 : -1;
                  
    if (timedout) {
      log_syncTimeout(mote_id, retry);
    }
    
    
    /* 
     * Set prev time to -1 unless we are retrying
     * This means we can still record 2 beacons after a timeout
     */
    if (!retry && SYNC_STATES[mote_id] != S_FINDN) 
      PREV_TIMES[mote_id] = -1L;
    
    if (QUEUE[mote_id] == 1 
        && SYNC_STATES[mote_id] == S_FINDN && next_id != mote_id) {
      
      /* 
       * If we were waiting for max_n but now switching channels
       * we will probably miss it so cancel that.
       */
      
      SYNC_STATES[mote_id] = S_NORMAL;
      Logger.appendString(csr.s2b("Cancel find n"));
      Logger.flush(Mote.WARN);
      
      /*
       * This makes it so the next beacon that arrives is classed
       * as the first beacon
       */
      PREV_TIMES[mote_id] = -1;
    }

    Source.sync_id = -1;
    
    // Finally update the radio 
    if (next_id > -1)
      Source.setRadioForSync(next_id);
    else
      log_noQueue();
  }
  
  /** Called when a packet is received, a null is received when the radio switches off */
  private static int onReceive(int flags, byte[] data, int len, int info, long time) {
    
  	// We want to know if we manually shut off the radio, if so do nothing.
    if (data == null && manual_off) {
      manual_off = false; return 0;
    }
    
    // Timeout
    if (data == null) {
    	
      pickNextSync(sync_id, true);
    	return 0;
    }
    
    // Beacon
    int number = data[11];
    int mote_id = sync_id;
    
    if (SYNC_STATES[mote_id] == S_FINDN) {
      /*
       *  We found n, if we got this far we are probably sure that this
       *  is the correct value
       */
      NS[mote_id] = number;

      log_foundN(mote_id, number);
      secondBeacon(mote_id, number, time);
      
      SYNC_STATES[mote_id] = S_NONE;
      return 0;
    }
  	
    // Record the state of the beacon
    if (PREV_TIMES[mote_id] == -1)
      firstBeacon(mote_id, number, time);
    else
      secondBeacon(mote_id, number, time);
    
    return 0;
      
  }

  /** Called after a packet is sent */
  private static int onSent(int flags, byte[] data, int len, int info, long time) {
    
    byte mote_id = (byte) (data[3]-0x11);
    LED.setState(mote_id, (byte) (1 - LED.getState(mote_id)));
    
    stopRadioManually();
    
    // Switch back the radio because we are in sync phase
    if (shouldWaitForSync())
      setRadioForSync(sync_id);
    
    return 0;
  }

  /**
   * Start a timer for a mote
   * @param mote_id
   * @param phase A *_PHASE const
   * @param abs_time The absolute time the timer should be tired
   */
  private static void startTimer(int mote_id, int phase, long abs_time, long offset) {
    Timer t = mote_id == 0 ? tsend0 :
              mote_id == 1 ? tsend1 :
              mote_id == 2 ? tsend2 : null;
    
    TIMER_TIMES[mote_id] = abs_time;
    t.setParam((byte) (mote_id | phase));
    t.setAlarmTime(abs_time + offset);
  }

  
  /** Called when a timer has fired */
  private static void timerCallback(byte param, long time) {
    int mote_id =  param & 0xF;
    /*
     * This method reports TIMER_TIME which is the time we wanted
     * the timer to call, without the offset
     */
    long timer_time = TIMER_TIMES[mote_id];
    
    if ((param & 0xF0) == SYNC_PHASE)
      startSyncPhaseFromTimer(mote_id, timer_time, time);
    else if ((param & 0xF0) == RECEP_PHASE)
      receptionPhase(mote_id, timer_time, time);
  }

  
  /** Starts syncing after the timer has run */
  private static void startSyncPhaseFromTimer(int mote_id, long sync_time, long time) {
    
    QUEUE[mote_id] = 1;
    
    if (shouldWaitForSync()) {
      /*
       * If a mote is already trying to sync then we should
       * not sync this mote but add it to a queue instead.
       */
      
      log_missSync(mote_id, sync_id);
      
      /*
       * We might have been expecting to get n here, in which case
       * we will probably miss n because we are queueing it for later. 
       * Just do a normal sync instead
       */
      if (SYNC_STATES[mote_id] == S_FINDN) {
        Logger.appendString(csr.s2b("Cancel n=1 for mote "));
        Logger.appendInt(mote_id);
        Logger.flush(Mote.WARN);
        SYNC_STATES[mote_id] = S_NORMAL;
        // This starts the sync phase again, so the next beacon will be
        // first beacon
        PREV_TIMES[mote_id] = -1;
      }
      
      return;
    }
    
    Logger.appendString(csr.s2b("Starting sync mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" from timer"));
    Logger.flush(Mote.WARN);
    
    setRadioForSync(mote_id);
  }

  /** Called when the timer has fired because of a reception phase */
  private static void receptionPhase(int mote_id, long reception_time, long time) {

    
    if (shouldWaitForSync()) {
      // I think sending a packet should override sync phase
      
      if (SYNC_STATES[sync_id] == S_FINDN) {
        /*
         * The syncing phase is waiting for n, if it misses the first beacon
         * then n will be WRONG which will mess up the reception phase
         * There is a chance here that switching to transmit will
         * miss the sync beacon. So I think we should try and find n next
         * time instead.
         */
        SYNC_STATES[sync_id] = S_NORMAL;
      }

      // Note: Make sure to start syncing again after transmission
      stopRadioManually();
    }
    
    /*
     * RECEP_TIMES[mote_id] is the time the timer should have started,
     * the timer may have been fired a bit late so don't use time.
     */
    
    log_recepPhase(mote_id, reception_time, time);
    
    log_sendPacket(mote_id);
    
    byte pan_id = PANIDS[mote_id];
      
    radio.setChannel(CHANNELS[mote_id]);
    radio.setPanId(pan_id, false);
    radio.startRx(Device.ASAP, 0, Time.currentTicks() + Time.toTickSpan(Time.SECONDS, 1));
    
    Util.set16le(xmit, 3, pan_id); // destination PAN address
    Util.set16le(xmit, 7, pan_id); // own PAN address 
    radio.transmit(Device.ASAP | Radio.TXMODE_POWER_MAX, xmit, 0, 12, 0);

    /*
     * If we wanted to sync again after a certain time, we could do it here
     */
    
    /* If we don't want the mote to sync then we sould start the reception
     * phase again in 1 cycle */
    if (SYNC_STATES[mote_id] == S_NONE) {
      long next_recep_time = reception_time + (11 + NS[mote_id]) * TIMES[mote_id];

      log_startRecepTimer(mote_id, next_recep_time);
      Logger.appendString(csr.s2b("11 + "));
      Logger.appendInt(NS[mote_id]);
      Logger.appendString(csr.s2b(" * "));
      Logger.appendLong(TIMES[mote_id]);
      Logger.flush(Mote.WARN);
      
      //RECEP_TIMES[mote_id] = next_recep_time;
      
      startTimer(mote_id, RECEP_PHASE, next_recep_time, PADDING_TICKS);
    }
    else {
      long sync_time = reception_time + 11 * TIMES[mote_id];
      startTimer(mote_id, SYNC_PHASE, sync_time, -PADDING_TICKS);
    }
  }
  
  /**
   * Starts the radio for syncing phase. Radio must be in standby 
   * at this point
   * @param mote_id
   */
  private static void setRadioForSync(int mote_id) {
    
    log_radioSync(mote_id);
    
    Source.sync_id = mote_id;
    
    // Set channel 
    radio.setChannel(CHANNELS[mote_id]);
    radio.setPanId(PANIDS[mote_id], false);
    
    radio.startRx(Device.ASAP, 0, Time.currentTicks() + MAX_BEACON_TICKS);
  }
  
  
  
  /*
   * 
   * Logging
   * 
   */
  


  private static void log_firstBeacon(int mote_id, int number) {
    Logger.appendString(csr.s2b("Receive first beacon for mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" where n is "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  private static void log_secondBeacon(int mote_id, int number) {
    Logger.appendString(csr.s2b("Receive second beacon for mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" where n is "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_radioSync(int mote_id) {
    Logger.appendString(csr.s2b("Waiting to sync mote"));
    Logger.appendInt(mote_id);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_recepPhase(int mote_id, long recep_time, long now_time) {
    Logger.appendString(csr.s2b("Starting recep phase for mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" at time "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, recep_time));
    Logger.appendString(csr.s2b("ms (+"));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, now_time-recep_time));
    Logger.appendString(csr.s2b("ms)"));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_timeDiff(int mote_id, long time_diff, long prev_time, int n_delta) {
    Logger.appendString(csr.s2b("Diff for mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" is "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, time_diff));
    Logger.appendString(csr.s2b("ms (prev_time was "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, prev_time));
    Logger.appendString(csr.s2b("ms and n_delta is "));
    Logger.appendInt(n_delta);
    Logger.appendString(csr.s2b(")"));
    Logger.flush(Mote.WARN);
  }

  private static void log_syncTimeout(int mote_id, boolean retry) {
    Logger.appendString(csr.s2b("Mote")); 
    Logger.appendInt(mote_id); 
    Logger.appendString(csr.s2b(" took too long to sync"));
    if (retry)
      Logger.appendString(csr.s2b(", but I will try again"));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_foundN(int mote_id, int number) {
    Logger.appendString(csr.s2b("I am sure that mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" has n = "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_sendPacket(int mote_id) {
    Logger.appendString(csr.s2b("Sending packet to mote"));
    Logger.appendInt(mote_id);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_noQueue() {
    Logger.appendString(csr.s2b("No queued motes. Sleep."));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_missSync(int mote_id, int cause_id) {
    Logger.appendString(csr.s2b("Mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" missed sync phase due to mote"));
    Logger.appendInt(cause_id);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_startRecepTimer(int mote_id, long at_time) {
    Logger.appendString(csr.s2b("Next recep phase for mote"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" should be at "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, at_time));
    Logger.appendString(csr.s2b("ms"));
    Logger.flush(Mote.WARN);
  }

}