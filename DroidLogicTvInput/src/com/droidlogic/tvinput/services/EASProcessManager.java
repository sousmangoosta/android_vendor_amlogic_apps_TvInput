package com.droidlogic.tvinput.services;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.HashMap;
import java.util.Map;
import android.net.Uri;
import android.util.Log;
import com.droidlogic.app.tv.ChannelInfo;
import java.util.ArrayList;
import android.content.Intent;
import android.media.tv.TvContract;
import com.droidlogic.app.tv.TvDataBaseManager;
import com.droidlogic.app.tv.EasEvent;

public class EASProcessManager{
    private static final String TAG = "EASProcessManager";

    private Context mContext;
    private ArrayList<ChannelInfo> channelList;
    private  Handler mHandler = null;
    private Uri mOriginalChannel = null;
    private boolean isEasInProgress = false;
    private static boolean DEBUG = true;
    private static final String MAJOR_NUM = "majorNum";
    private static final String MINOR_NUM = "minorNum";
    private String mCurDispNum = null;
    private String mInputId = null;
    private Uri mCurrentUri = null;
    private EasProcessCallback mCallback;
    private TvDataBaseManager mTvDataBaseManager = null;

    public EASProcessManager(Context context) {
        Log.d(TAG,"***** eas process manager *****");
        mContext = context;
        mTvDataBaseManager = new TvDataBaseManager(mContext);
        mHandler = new Handler();
    }

    public void SetCurDisplayNum(String dispNum) {
        mCurDispNum = dispNum;
    }
    public void SetCurInputId(String inputId) {
        mInputId = inputId;
    }
    public void SetCurUri(Uri uri) {
        mCurrentUri = uri;
    }

    public void setCallback(EasProcessCallback callback) {
        mCallback = callback;
    }
    public boolean isEasInProgress() {
        return isEasInProgress;
    }
    //private HashMap<String,Integer> getChanelMajorAndMinorNumber(ChannelInfo channel){
    private HashMap<String,Integer> getChanelMajorAndMinorNumber(String dispNum){
        HashMap<String,Integer> channelMajorAndMinorNum = new HashMap<>();
        ChannelNumber channelNum = new ChannelNumber();
        //ChannelNumber channelNumber = channelNum.parseChannelNumber(channel.getDisplayNumber());
        ChannelNumber channelNumber = channelNum.parseChannelNumber(dispNum);
        channelMajorAndMinorNum.put(MAJOR_NUM,Integer.parseInt(channelNumber.majorNumber));
        channelMajorAndMinorNum.put(MINOR_NUM,Integer.parseInt(channelNumber.minorNumber));
        return channelMajorAndMinorNum;
    }

    private void setOriginalChannel(){
        if (!isEasInProgress) {
            mOriginalChannel = mCurrentUri;
            if (DEBUG) Log.d(TAG,"setOriginalChannel = "+mOriginalChannel);
        }
    }

    private boolean isAlreadyTuneToDetailsChannel(int majorNum, int minorNum){
        if (DEBUG) Log.d(TAG,"isAlreadyTuneToDetailsChannel,mCurDispNum = "+mCurDispNum);
        HashMap<String,Integer> curChannelNum = getChanelMajorAndMinorNumber(mCurDispNum);
        //HashMap<String,Integer> curChannelNum = getChanelMajorAndMinorNumber(mCurrentSession.mCurrentChannel);
        if (DEBUG) Log.d(TAG,"curChannelNum.major = "+curChannelNum.get(MAJOR_NUM)+", majr num = "+majorNum);
        if (curChannelNum.get(MAJOR_NUM) == majorNum && curChannelNum.get(MINOR_NUM) == minorNum)
            return true;
        else
            return false;
    }

    private ChannelInfo getDetailsChannel(int majorNum, int minorNum){
        channelList = mTvDataBaseManager.getChannelList(mInputId, ChannelInfo.COMMON_PROJECTION, null, null);
        if (channelList != null) {
            for (ChannelInfo singleChannel : channelList) {
                //HashMap<String,Integer> singleChannelNum = getChanelMajorAndMinorNumber(singleChannel);
                String curDispNum = null;
                if (singleChannel != null)
                    curDispNum = singleChannel.getDisplayNumber();
                if (DEBUG) Log.d(TAG,"getDetailsChannel,curDispNum = "+curDispNum);
                HashMap<String,Integer> singleChannelNum = getChanelMajorAndMinorNumber(curDispNum);
                if (singleChannelNum.get(MAJOR_NUM) == majorNum && singleChannelNum.get(MINOR_NUM) == minorNum) {
                    return singleChannel;
                }
            }
        }
        return null;
    }

    private String getAlertText(EasEvent easEvent){
        if (easEvent.multiTextCount != 0) {
            String easText = "";
            for (EasEvent.MultiStr multiStr:easEvent.multiText) {
                for (int ch:multiStr.compressedStr) {
                    if (ch >= 0 && ch <= 255) {
                        easText += Character.toString((char)ch);
                    }
                }
            }
            if (DEBUG) Log.d(TAG,"easText = "+easText);
            return easText;
        }
        return null;
    }

    private void showAlertText(String easText){
        mCallback.onUpdateEasText(easText);
    }

    public void processDetailsChannelAlert(EasEvent easEvent){
        if (DEBUG) Log.d(TAG,"processDetailsChannelAlert,time = "+easEvent.alertMessageTimeRemaining+
            ",isEasInProgress = "+isEasInProgress);
        int majorNum = easEvent.detailsMajorChannelNumber;
        int minorNum = easEvent.detailsMinorChannelNumber;
        int timeToOriginalChannelInMillis = easEvent.alertMessageTimeRemaining * 1000;
        setOriginalChannel();
        isEasInProgress = true;
        mCallback.onEasEnd();
        mHandler.removeCallbacks(mTuneToOriginalChannelRunnable);
        mHandler.removeCallbacks(mCancelEasAlertTextDisplayRunnable);
        if (!isAlreadyTuneToDetailsChannel(majorNum, minorNum)) {
            ChannelInfo detailChannel = getDetailsChannel(majorNum, minorNum);
            if (detailChannel != null) {
                if (DEBUG) Log.d(TAG,"tune to detail channel");
                Uri channelUri = TvContract.buildChannelUri(detailChannel.getId());
                launchLiveTv(channelUri);
            }else {
                if (DEBUG)
                    if (DEBUG) Log.d(TAG,"detail channel is unavailable");
            }
        }
        showAlertText(getAlertText(easEvent));
        if (timeToOriginalChannelInMillis != 0) {
            mHandler.postDelayed(mTuneToOriginalChannelRunnable,timeToOriginalChannelInMillis);
            mHandler.postDelayed(mCancelEasAlertTextDisplayRunnable,timeToOriginalChannelInMillis);
        }
    }

    private final Runnable mTuneToOriginalChannelRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) Log.d(TAG,"mTuneToOriginalChannelRunnable");
                    isEasInProgress = false;
                        mCallback.onEasEnd();
                    launchLiveTv(mOriginalChannel);
                }
            };

    private final Runnable mCancelEasAlertTextDisplayRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) Log.d(TAG,"mCancelEasAlertTextDisplayRunnable");
                    isEasInProgress = false;
                    mCallback.onEasEnd();
                    showAlertText(null);
                }
            };


    private void launchLiveTv(Uri uri)    {
       if (DEBUG) Log.d(TAG, "launchLiveTv="+uri);

           Intent intent = new Intent(Intent.ACTION_VIEW);
           intent.setData(uri);
           mContext.startActivity(intent);
   }

    public  class ChannelNumber {
        public   String PRIMARY_CHANNEL_DELIMITER = "-";
        public  String[] CHANNEL_DELIMITERS = {"-", ".", " "};


        public String majorNumber;
        public boolean hasDelimiter;
        public String minorNumber;

        public ChannelNumber() {
            reset();
        }

        public ChannelNumber(String major, boolean hasDelimiter, String minor) {
            setChannelNumber(major, hasDelimiter, minor);
        }

        public void reset() {
            setChannelNumber("", false, "");
        }

        public void setChannelNumber(String majorNumber, boolean hasDelimiter, String minorNumber) {
            this.majorNumber = majorNumber;
            this.hasDelimiter = hasDelimiter;
            this.minorNumber = minorNumber;
        }

        @Override
        public String toString() {
            if (hasDelimiter) {
                return majorNumber + PRIMARY_CHANNEL_DELIMITER + minorNumber;
            }
            return majorNumber;
        }

        public  ChannelNumber parseChannelNumber(String number) {
            if (DEBUG) Log.d(TAG,"parseChannelNumber:"+number);
            if (number == null) {
                return null;
            }
            ChannelNumber ret = new ChannelNumber();
            int indexOfDelimiter = -1;
            for (String delimiter : CHANNEL_DELIMITERS) {
                indexOfDelimiter = number.indexOf(delimiter);
                if (indexOfDelimiter >= 0) {
                break;
                }
            }
            if (indexOfDelimiter == 0 || indexOfDelimiter == number.length() - 1) {
                return null;
            }
            if (indexOfDelimiter < 0) {
                ret.majorNumber = number;
                if (!isInteger(ret.majorNumber)) {
                    return null;
                }
            } else {
                ret.hasDelimiter = true;
                ret.majorNumber = number.substring(0, indexOfDelimiter);
                ret.minorNumber = number.substring(indexOfDelimiter + 1);
                if (!isInteger(ret.majorNumber) || !isInteger(ret.minorNumber)) {
                    return null;
                }
            }
            return ret;
        }

        public  boolean isInteger(String string) {
            try {
                Integer.parseInt(string);
            } catch(NumberFormatException e) {
                return false;
            } catch(NullPointerException e) {
                return false;
            }
            return true;
        }
    }

    public abstract static class EasProcessCallback {

        public void onEasStart() {
        }
        public void onEasEnd() {
        }
        public void onUpdateEasText(String inputId) {
        }
    }
}