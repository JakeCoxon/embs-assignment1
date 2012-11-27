package embs;

import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;

public class Source {

  private static final int MOTE0 = 0;
  private static final int MOTE1 = 1;
  private static final int MOTE2 = 2;

  private static final int S_NORMAL = 0;
  private static final int S_FINDN = 1;
  private static final int S_NONE = 2;

  private static final byte[] CHANNELS = new byte[] {0, 1, 2};
  private static final byte[] PANIDS = new byte[] {0x11, 0x12, 0x13};
  private static final long[] TIMES = new long[] {-1, -1, -1};
  private static final long[] SYNC_TIMES = new long[] {-1, -1, -1};
  private static final long[] RECEP_TIMES = new long[] {-1, -1, -1};
  private static final int[] NS = new int[] {-2, -2, -2};
  private static final int[] SYNC_STATES = new int[] {S_NORMAL, S_NORMAL, S_NORMAL};

  private static final int SYNC_PHASE = 0x10;
  private static final int RECEP_PHASE = 0x20;
  
  private static final long MAX_BEACON_TICKS = Time.toTickSpan(Time.MILLISECS, 1500);
  private static final long PADDING_TICKS = Time.toTickSpan(Time.MILLISECS, 50);
  
  private static long prev_time = -1L;
  private static int prev_n = -1;
  private static int sync_id = -1;
  

  static Radio radio = new Radio();

  private static byte[] xmit;

  private static byte my_address = 0x10;
  
  /* Timer per mote */
  private static Timer tsend0 = new Timer();
  private static Timer tsend1 = new Timer();
  private static Timer tsend2 = new Timer();
  
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
    Util.set16le(xmit, 9, 0x10); // own short address
//  Util.set16le(xmit, 3, panid); // destination PAN address 
//  Util.set16le(xmit, 7, panid); // own PAN address 
    
    
    tsend0.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time);
      }
    });
    tsend1.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time);
      }
    });
    tsend2.setCallback(new TimerEvent(null){
      public void invoke(byte param, long time){
        Source.timerCallback(param, time);
      }
    });
    
    // Radio handlers
    radio.setRxHandler(new DevCallback(null){
      public int invoke (int flags, byte[] data, int len, int info, long time) {
        return Source.onReceive(flags, data, len, info, time);
      }
    });
    radio.setTxHandler(new DevCallback(null) {
      public int invoke(int flags, byte[] data, int len, int info, long time) {
        return Source.onSent(flags, data, len, info, time);
      }
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

  private static void firstBeacon(int mote_id, int number, long time) {
    log_firstBeacon(mote_id, number);
    
    /*
     * We found n, we should be sure that this is the correct
     * value
     */
    if (SYNC_STATES[mote_id] == S_FINDN)
    {
      log_foundN(mote_id, number);
      
      NS[mote_id] = number;
      SYNC_STATES[mote_id] = S_NONE;
    }
    
    prev_time = time;
    prev_n = number;
    
    /*
     * If number is 1 then we know there is no more syncs but we don't have t
     */
    if (number == 1) 
    	pickNextSync(false, time);
  }
  
  private static void secondBeacon(int mote_id, int number, long time) {

    log_secondBeacon(mote_id, number);
    

    // Completed 1 normal sync, so next time try and find n
    if (SYNC_STATES[mote_id] == S_NORMAL)
      SYNC_STATES[mote_id] = S_FINDN;
    
    
    
    /*
     * Record time difference (t) and set up the next sync time for 
     * this mote. Start the timer for reception phase in n*t time
     * but add 1% of t to make sure we are actually in the time frame.
     * (Sometimes the timer starts 0.006ms before the time frame)
     */
    
    long time_diff = TIMES[mote_id] = time - prev_time;
    
    // Divide this up by the difference in n. Usually this will just
    // be 1 but there's a small chance we missed a beacon while we
    // were sending one.
    time_diff /= prev_n - number;
    
    
    long reception_time = RECEP_TIMES[mote_id] = time + number * time_diff;
    SYNC_TIMES[mote_id] = reception_time + 11 * time_diff;
    
    log_timeDiff(mote_id, time_diff, reception_time);
    
    startTimer(mote_id, RECEP_PHASE, reception_time + PADDING_TICKS);

    prev_time = -1L;
    
    pickNextSync(false, time);
  }
  
  private static void pickNextSync(boolean timedout, long time) {

    if (radio.getState() != Radio.S_STDBY)
      Source.stopRadioManually();
    
    /*
     *  Pick a mote that needs syncing:
     *  We should try this mote again is there is a possibility that we
     *  could receive the second sync beacon
     *  
     *  Otherwise pick another mote that needs to be synced, with this
     *  mote as a last resort.
     */
    boolean retry = timedout && time - prev_time < MAX_BEACON_TICKS 
    		                     && prev_time != -1 && prev_n != 1;
    
    int next_id = retry ? sync_id :
        TIMES[(sync_id + 2) % 3] == -1 ? (sync_id + 2) % 3 :
        TIMES[(sync_id + 1) % 3] == -1 ? (sync_id + 1) % 3 :
        TIMES[(sync_id + 0) % 3] == -1 ? (sync_id + 0) % 3 : -1;
                  
    if (timedout) {
      log_syncTimeout(sync_id, retry);
    }
    
    sync_id = -1;
    if (!retry) prev_time = -1L;
    
    if (next_id > -1) {
      Source.setRadioForSync(next_id);
    }
    else {
      log_noQueue();
    }
  }
  private static int onReceive (int flags, byte[] data, int len, int info, long time) {
    
  	// We want to know if we manually shut off the radio, if so do nothing.
    if (data == null && manual_off) {
      manual_off = false; return 0;
    }
    
    // Timeout
    if (data == null) {
    	
    	pickNextSync(true, time);
    	
    }
    else { // Beacon
	  	
    	int number = data[11];
	  	
	    if (prev_time == -1)
	      firstBeacon(sync_id, number, time);
	    else
	      secondBeacon(sync_id, number, time);
	    
    }
    
    return 0;
      
  }

  protected static int onSent(int flags, byte[] data, int len, int info, long time) {
    
    byte mote_id = (byte) (data[3]-0x11);
    LED.setState(mote_id, (byte) (1 - LED.getState(mote_id)));
    
    stopRadioManually();
    
    log_sentPacket(mote_id);
    
    // Switch back the radio because we are in sync phase
    if (shouldWaitForSync())
      setRadioForSync(sync_id);
    
    return 0;
  }

  private static void startTimer(int mote_id, int phase, long abs_time) {
    Timer t = mote_id == 0 ? tsend0 :
              mote_id == 1 ? tsend1 :
              mote_id == 2 ? tsend2 : null;
    
    t.setParam((byte) (mote_id | phase));
    t.setAlarmTime(abs_time);
  }

  private static void timerCallback(byte param, long time) {
    if ((param & 0xF0) == SYNC_PHASE)
      startSyncPhase((byte) (param & 0xF), time);
    else if ((param & 0xF0) == RECEP_PHASE)
      receptionPhase((byte) (param & 0xF), time);
  }
  
  private static void startSyncPhase(byte param, long time) {
    int mote_id = param;
    
    if (shouldWaitForSync()) {
      /*
       * If a mote is already trying to sync then we should
       * not sync this mote but add it to a queue instead
       */
      
      log_missSync(mote_id, sync_id);
      
      /*
       * We might have been expecting to get n here, in which case
       * we will probably miss n. Just do a normal sync instead
       */
      if (SYNC_STATES[mote_id] == S_FINDN) 
        SYNC_STATES[mote_id] = S_NORMAL;
      
      TIMES[mote_id] = -1; // queue
      return;
    }
    
    setRadioForSync(mote_id);
  }

  private static void receptionPhase(byte param, long time) {

    
    if (shouldWaitForSync()) {
      // I think sending a packet should override sync phase
      
      if (SYNC_STATES[sync_id] == S_FINDN) {
        /*
         * The syncing phase is waiting for n, if it misses the first beacon
         * then n will be WRONG which will mess up the reception phase
         * There is a chance here that switching to transmit (~50ms) will
         * miss the sync beacon. So I think we should try and find n next
         * time instead.
         */
        SYNC_STATES[sync_id] = S_NORMAL;
      }

      // Remember to start radio back up after transmission
      stopRadioManually();
    }
    
    /*
     * RECEP_TIMES[mote_id] is the time the timer should have started,
     * the timer may have been fired a bit late so don't use time.
     */
    int mote_id = param;
    
    log_recepPhase(mote_id, RECEP_TIMES[mote_id], time);
    
    byte pan_id = PANIDS[mote_id];
      
    radio.setChannel(CHANNELS[mote_id]);
    radio.setPanId(pan_id, false);
    radio.startRx(Device.ASAP, 0, Time.currentTicks() + Time.toTickSpan(Time.SECONDS, 1));
    
    Util.set16le(xmit, 3, pan_id); // destination PAN address
    Util.set16le(xmit, 7, pan_id); // own PAN address 
    radio.transmit(Device.ASAP | Radio.TXMODE_POWER_MAX, xmit, 0, 12, 0);

    
    
    /*
     * If we wanted to sync again after 1 minute or so, we could do it here
     */
    
    if (NS[mote_id] > -1) {
      long next_recep_time = RECEP_TIMES[mote_id] + (11 + NS[mote_id]) * TIMES[mote_id];

      log_startRecepTimer(mote_id, next_recep_time);
      RECEP_TIMES[mote_id] = next_recep_time;
      
      startTimer(mote_id, RECEP_PHASE, next_recep_time + PADDING_TICKS);
    }
    else
      startTimer(mote_id, SYNC_PHASE, SYNC_TIMES[mote_id] - PADDING_TICKS);
  }
  
  /**
   * Starts the radio for syncing phase. Radio must be 
   * in standby at this point
   * @param mote_id
   */
  private static void setRadioForSync(int mote_id) {
    
    log_radioSync(mote_id);
    
    Source.sync_id = mote_id;
    
    // Set channel 
    radio.setChannel(CHANNELS[mote_id]);
    radio.setPanId(PANIDS[mote_id], false);
    
    radio.startRx(Device.ASAP, 0, Time.currentTicks() + MAX_BEACON_TICKS * 2);
  }
  
  
  
  /*
   * 
   * Logging
   * 
   */
  


  private static void log_firstBeacon(int mote_id, int number) {
    Logger.appendString(csr.s2b("Receive first beacon for mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" where n is "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  private static void log_secondBeacon(int mote_id, int number) {
    Logger.appendString(csr.s2b("Receive second beacon for mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" where n is "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_radioSync(int mote_id) {
    Logger.appendString(csr.s2b("Waiting to sync mote#"));
    Logger.appendInt(mote_id);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_recepPhase(int mote_id, long recep_time, long now_time) {
    Logger.appendString(csr.s2b("Reception phase for mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" at time "));
    Logger.appendLong(recep_time);
    Logger.appendString(csr.s2b(" (+"));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, now_time-recep_time));
    Logger.appendString(csr.s2b("ms)"));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_timeDiff(int mote_id, long time_diff, long reception_time) {
    Logger.appendString(csr.s2b("Diff for mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" is "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, time_diff));
    Logger.appendString(csr.s2b(" so predict recep time is "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, reception_time));
    Logger.appendString(csr.s2b("ms"));
    Logger.flush(Mote.WARN);
  }

  private static void log_syncTimeout(int mote_id, boolean retry) {
    Logger.appendString(csr.s2b("Mote#")); 
    Logger.appendInt(mote_id); 
    Logger.appendString(csr.s2b(" took too long to sync"));
    if (retry)
      Logger.appendString(csr.s2b(", but I will try"));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_foundN(int mote_id, int number) {
    Logger.appendString(csr.s2b("NS["));
    Logger.appendInt(sync_id);
    Logger.appendString(csr.s2b("] = "));
    Logger.appendInt(number);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_sentPacket(int mote_id) {
    Logger.appendString(csr.s2b("** PACKET SENT TO MOTE#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" **"));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_noQueue() {
    Logger.appendString(csr.s2b("No queued motes. Sleep."));
    Logger.flush(Mote.WARN);
  }
  
  private static void log_missSync(int mote_id, int cause_id) {
    Logger.appendString(csr.s2b("Mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" missed sync phase due to mote#"));
    Logger.appendInt(cause_id);
    Logger.flush(Mote.WARN);
  }
  
  private static void log_startRecepTimer(int mote_id, long at_time) {
    Logger.appendString(csr.s2b("Next phase for mote#"));
    Logger.appendInt(mote_id);
    Logger.appendString(csr.s2b(" at "));
    Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, at_time));
    Logger.appendString(csr.s2b("ms)"));
    Logger.flush(Mote.WARN);
  }

}