package com.ibm.cio.util;

import android.app.Application;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;

import com.ibm.cio.audio.player.PlayerUtil;
import com.ibm.cio.opus.OggOpus;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.xiph.speex.PcmWaveWriter;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.io.RandomAccessFile;

import java.lang.reflect.Array;
import java.util.LinkedList;
import java.util.List;

public class TTSPlugin extends Application {
	private static final String TAG = TTSPlugin.class.getName();

	public static final String CODEC_WAV = "audio/wav";
	public static final String CODEC_OPUS = "audio/opus";

	Context ct;
	AudioManager am;
	private String username;
	private String password;
    private String token;
    private String voice;
	private String content;
	private String codec;
	private String language;
	private int sampleRate;
	private String server;
	
	private AudioTrack audioTrack;
	private MediaPlayer	wavPlayer = null;

	//private static final int MIN_FRAME_COUNT = 600;

	public TTSPlugin(){
		this.codec = CODEC_WAV;
        // By default, the sample rate would be detected by the SDK if the value is set to zero
        // However, the metadata is not reliable, need to decode at the maximum sample rate
        this.sampleRate = 48000;
	}

    /**
     * Set codec
     * @param codec
     */
	public void setCodec(String codec){
		this.codec = codec;
	}

    /**
     * Set sample rate
     * @param val
     */
    public void setSampleRate(int val){
        this.sampleRate = val;
    }

	private void stopAudioPlayer(JSONArray arguments) {
		Log.i(TAG, "stop all AudioPlayer");
		//stop welcome
		if (wavPlayer!=null && wavPlayer.isPlaying()) {
			wavPlayer.stop();
			//wavPlayer.release();
		}
		//stop tts
		PlayerUtil.ins8k.stop();
		stopTtsPlayer();
	}

	private void stopTtsPlayer() {
//		synchronized (this) {
			if (audioTrack!=null && audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED ) {
				Log.i(TAG, "stopTtsPlayer");
				// IMPORTANT: NOT use stop()
				// For an immediate stop, use pause(), followed by flush() to discard audio data that hasn't been played back yet.
				audioTrack.pause();
				audioTrack.flush();
				//audioTrack.release();
		 	}			
//		}
	}
	


	/*
	private void playWav(JSONArray arguments) {
		System.out.println("playWav welcome");
		soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
		AudioManager am = (AudioManager) this.ctx.getSystemService(this.ctx.getApplicationContext().AUDIO_SERVICE);
		float actualVolume = (float) am.getStreamVolume(AudioManager.STREAM_MUSIC);
		float maxVolume = (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		final float volume = actualVolume / maxVolume;
		soundPool.setOnLoadCompleteListener(new OnLoadCompleteListener() {
			
			@Override
			public void onLoadComplete(SoundPool sPool, int sampleId, int status) {
				// TODO Auto-generated method stub
				System.out.println("playWav soundPool onLoadComplete");
				streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f);
			}
		});
		soundId = soundPool.load(this.ctx.getApplicationContext(), R.raw.welcome_8000, 1);
	}
	*/

	/**
	 * Text to speech
	 * @param arguments
	 */
	public void tts(String[] arguments) {
		Log.i(TAG, "Requesting TTS...");
		try {
			parseParams(arguments);
		} catch (Exception e) {
			e.printStackTrace();
		}

        if(this.sampleRate > 0){
            this.initPlayer();
        }

		TTSThread thread = new TTSThread();
		thread.setName("TTSThread");
		thread.start();
	}

    private void initPlayer(){
        stopTtsPlayer();
        // IMPORTANT: minimum required buffer size for the successful creation of an AudioTrack instance in streaming mode.
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

        Log.i(TAG, "Construct AudioTrack, sampleRate = " + sampleRate + ", bufferSize=" + bufferSize);

        synchronized (this) {
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM);
            Log.i(TAG, "TTS after new AudioTrack: " + audioTrack);
            // Start playback
            if (audioTrack != null)
                audioTrack.play();
        }
    }

	private void parseParams(String[] arguments){
		int i = 0;
		this.server = arguments[i++];
		this.username = arguments[i++];
		this.password = arguments[i++];
        this.voice = arguments[i++];
		this.content = arguments[i++];
        this.token = arguments[i++];
	}

    /**
	 * Post text data to iTrans server and get returned audio data
	 * @param server iTrans server
	 * @param username
	 * @param password
	 * @param content
	 * @return {@link HttpResponse}
	 * @throws Exception
	 */
	public static HttpResponse createPost(String server, String username, String password, String token, String content, String voice, String codec) throws Exception {
        String url = server;

        //HTTP GET Client
        HttpClient httpClient = new DefaultHttpClient();
        //Add params
        List<BasicNameValuePair> params = new LinkedList<BasicNameValuePair>();
        params.add(new BasicNameValuePair("text", content));
        params.add(new BasicNameValuePair("voice", voice));
        params.add(new BasicNameValuePair("accept", codec));
        HttpGet httpGet = new HttpGet(url+"?"+ URLEncodedUtils.format(params, "utf-8"));
        // use token based authentication if possible, otherwise Basic Authentication will be used
        if (token != null) {
            Log.d(TAG, "using token based authentication");
            httpGet.setHeader("X-Watson-Authorization-Token",token);
        } else {
            Log.d(TAG, "using basic authentication");
            httpGet.setHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(username, password), "UTF-8", false));
        }
        //httpGet.setHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(username, password), "UTF-8", false));
        HttpResponse executed = httpClient.execute(httpGet);

		return executed;
	}

    /**
     * Get storage path
     * @return
     */
	private String getBaseDir() {
        String baseDir;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            baseDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        } else {
            baseDir = "/data/data/" + getApplicationContext().getPackageName() + "/";
        }

        return baseDir;
    }

	/**
	 * Thread to post text data to iTrans server and play returned audio data 
	 * @author chienlk
	 *
	 */
	public class TTSThread extends Thread {
		@Override
		public void run() {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
			
			HttpResponse post;
			try {
				post = createPost(server, username, password, token, content, voice, codec);
		        InputStream is = post.getEntity().getContent();

				byte[] temp;
				if(codec == CODEC_WAV) {
					temp = analyzeWavData(is);
					audioTrack.write(temp, 0, temp.length);
				}
				else if(codec == CODEC_OPUS){

					String inFilePath = getBaseDir()+"Watson.opus";
					String outFilePath = getBaseDir()+"Watson.pcm";
					File inFile = new File(inFilePath);
					File outFile = new File(outFilePath);
					outFile.deleteOnExit();
					inFile.deleteOnExit();

					RandomAccessFile inRaf = new RandomAccessFile(inFile, "rw");

					inRaf.write(IOUtils.toByteArray(is));

                    sampleRate = OggOpus.decode(inFilePath, outFilePath, sampleRate); // zero means to detect the sample rate by decoder

                    Logger.w(TAG, "*** Detected sample rate="+ sampleRate + " Hz ***");
                    initPlayer();

					RandomAccessFile outRaf = new RandomAccessFile(outFile, "r");

					temp = new byte[(int)outRaf.length()];

					int outLength = outRaf.read(temp);

					audioTrack.write(temp, 0, temp.length);

					inRaf.close();
					outRaf.close();
				}
                is.close();

			} catch (Exception e) {
				e.printStackTrace();
			} finally {
                Log.i(TAG, "call stop audioTrack");
				if (audioTrack != null && audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
//					audioTrack.stop();
					audioTrack.release();
//					System.out.println("tts after release: " + audioTrack + ", state: " + audioTrack.getState());
				}
			}
		}
	}

    /**
     * Analyze sample rate and return the PCM data
     * @param i
     * @return
     */
	public byte[] analyzeWavData(InputStream i){
		try {
			int headSize=44, metaDataSize=48;
            byte[] data = IOUtils.toByteArray(i);
            if(data.length < headSize){
                throw new IOException("Wrong Wav header");
            }

            if(this.sampleRate == 0) {
                this.sampleRate = readInt(data, 24); // 24 is the position of sample rate in wav format
                this.initPlayer();
            }

            int destPos = headSize + metaDataSize;
            int rawLength = data.length - destPos;

            byte[] d = new byte[rawLength];
			System.arraycopy(data, destPos, d, 0, rawLength);
            return d;
		} catch (IOException e) {
			Log.d(TAG,"Error while formatting header");
		}
		return new byte[0];
	}

    public byte[] stripHeaderAndSaveWav(InputStream i) {
        byte[] d = new byte[0];
        try {
            int headSize=44;
            int metaDataSize=48;
            i.skip(headSize+metaDataSize);
            d = IOUtils.toByteArray(i);
        } catch (IOException e) {
            Log.d(TAG,"Error while formatting header");
        }
        return saveWav(d);
    }

    public byte[] saveWav(byte[] d){
        PcmWaveWriter wR = new PcmWaveWriter(sampleRate, 1);
        return wR.saveWav(d, sampleRate, 1, 16);
    }

    void saveWavFile(byte[] d) {
        String fileName = getBaseDir() + "a.wav";
        try {
            PcmWaveWriter wR = new PcmWaveWriter(sampleRate, 1);
            wR.open(fileName);
            wR.saveWavFile(d,sampleRate,1,16);
            wR.close();
            Log.i(TAG, "save file OK");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            Log.d(TAG, "save file FAIL");
            e.printStackTrace();
        }
    }

    /**
     * Converts Little Endian (Windows) bytes to an int (Java uses Big Endian).
     * @param data the data to read.
     * @param offset the offset from which to start reading.
     * @return the integer value of the reassembled bytes.
     */
    protected static int readInt(final byte[] data, final int offset)
    {
        return (data[offset] & 0xff) |
                ((data[offset+1] & 0xff) <<  8) |
                ((data[offset+2] & 0xff) << 16) |
                (data[offset+3] << 24); // no 0xff on the last one to keep the sign
    }

    /**
     * Converts Little Endian (Windows) bytes to an short (Java uses Big Endian).
     * @param data the data to read.
     * @param offset the offset from which to start reading.
     * @return the integer value of the reassembled bytes.
     */
    protected static int readShort(final byte[] data, final int offset)
    {
        return (data[offset] & 0xff) |
                (data[offset+1] << 8); // no 0xff on the last one to keep the sign
    }
}
