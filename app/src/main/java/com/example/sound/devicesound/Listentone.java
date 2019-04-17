package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.min;
import static java.lang.Math.round;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }

    private double findFrequency(double[] toTransform){ //dominat
        int len = toTransform.length;
       // double[] real = new double[len];
       // double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD); //복소수
        Double[] freq = this.fftfreq(complx.length, 1);

        for(int i=0 ; i<complx.length ; i++){
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum*realNum)+(imgNum*imgNum));
        }

        double peak_freq = 0 ; //최댓값의 인덱스 저장하는 변수
        double max = 0;
        int max_index=0;
        for(int i=0 ; i<mag.length ; i++){
            if(mag[i]>max) {
                max = mag[i];
                max_index=i;
            }
        }
        peak_freq = freq[max_index];
        return Math.abs(peak_freq*mSampleRate);
    }

    private Double[] fftfreq(int length, int i) { //복소수를 실수로 변환
        Double[] freq = new Double[length+1];
        double fr = (double)1/(double)(length*i);
        double fr2 = -fr;
        int count=0;
        if(length%2==0){ //length 길이가 `짝수일때
            for(int j=0 ; j<=length/2 ; j++){
                freq[count] = ((double) j)*fr;
                count++;
            }
            for(int j=length/2 ; j>0 ; j--){
                freq[count] = ((double) j)*fr2;
                count++;
            }
        }
        else { //length 길이가 홀수일때
            for (int j = 0; j <= (length + 1) / 2; j++) {
                if (j == (length + 1) / 2 - 1) {
                    freq[count] = ((double) length - 1) / (2.0) * fr;
                    count++;
                } else {
                    freq[count] = ((double) j) * fr;
                    count++;
                }
            }
            for (int j = (length + 1) / 2; j > 0; j--) {
                if (j == (length + 1) / 2) {
                    freq[count] = (-1.0) * ((double) length - 1) / (2.0) * fr;
                    count++;
                } else {
                    freq[count] = ((double) -j) * fr2;
                    count++;
                }
            }
    }
        return freq;
    }

    public ArrayList<Integer> decode_bitchunks(int chunk_bits, ArrayList<Integer> chunks){
        ArrayList<Integer> out_bytes = new ArrayList<>();
        int next_read_chunk = 0;
        int next_read_bit = 0;
        int _byte = 0;
        int bits_left = 0;

        while(next_read_chunk<chunks.size()){
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = Math.min(bits_left,can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            _byte <<= to_fill;
            int shifted = chunks.get(next_read_chunk) & (((1 << to_fill) -1 ) << offset);
            _byte |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if(bits_left<=0){
                out_bytes.add(_byte);
                _byte = 0;
                bits_left = 8;
            }
            if(next_read_bit >= chunk_bits){
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }
        return out_bytes;
    }

    public ArrayList<Integer> extract_packet(ArrayList<Double> freq){
        ArrayList<Double> freqs = new ArrayList<>();
        ArrayList<Integer> bit_chunks = new ArrayList<>();
        ArrayList<Integer> bit_chunks2 = new ArrayList<>();

        for(int i=1 ; i<freq.size() ; i++){
            if(i%2!=0){
                freqs.add(freq.get(i));
            }
        }
        for(int i=0 ; i<freqs.size() ; i++){
            int f = Integer.parseInt(String.valueOf(round((freqs.get(i)-START_HZ)/STEP_HZ)));
            bit_chunks.add(f);
        }
        int sq = 1;
        for(int j=0 ; j<BITS ; j++) sq *= 2;
        for(int i=1 ; i<bit_chunks.size() ; i++){
            if(bit_chunks.get(i)>=0 && bit_chunks.get(i) < sq){
                bit_chunks2.add(bit_chunks.get(i));
            }
        }
        return decode_bitchunks(BITS,bit_chunks2);
    }

    public void PreRequest() {
        int blocksize = findPowerSize((int)(long)Math.round(interval/2*mSampleRate));
        short[] buffer = new short[blocksize]; //소리데이터
        ArrayList<Double> packet = new ArrayList<>();
        ArrayList<Integer> byte_stream = new ArrayList<>();
        ArrayList<Character> byte_stream2 = new ArrayList<>();

        while(true){
            int bufferedReadResult = mAudioRecord.read(buffer,0,blocksize);

            //소리데이터 버퍼를 findfrequency에 담고
            //findfrequency 소리데이터를 정규화에서 내뿜는 과정이 python에선 dominant
            double buffer2[] = new double[blocksize];
            for(int i=0 ; i<buffer.length ; i++){
                buffer2[i] = (double)buffer[i];
            }
            //double형 buffer2 배열에 buffer1 원소들 복사
            double dom = findFrequency(buffer2);
            //Log.d("ListenToneCHUNK1",Double.toString(dom));
            if(startFlag && match(dom,HANDSHAKE_END_HZ)){
                byte_stream = extract_packet(packet);
                for(int i=0 ; i<byte_stream.size() ; i++){
                    char byte_stream3 = (char)(int)byte_stream.get(i);
                    Log.d("ListenTone_byte_stream",String.valueOf(byte_stream3));
                    byte_stream2.add(byte_stream3);
                }
                display(byte_stream2);
                packet.clear();
                startFlag=false;
            }
            else if(startFlag){
                packet.add(dom);
                Log.d("ListenToneCHUNK2",Double.toString(dom));
            }
            else if(match(dom,HANDSHAKE_START_HZ)){
                startFlag=true;
            }
        }
    }

    private void display(ArrayList<Character> byte_stream) {
        ArrayList<Character> letter = byte_stream;
        String str="";
        for(int i=0 ; i<letter.size() ; i++){
            str += Character.toString(letter.get(i));
        }
        Log.d("ListenTone_result",str);
    }

    public boolean match(double a, int b) {
        return Math.abs((int)a-b)<20;
    }

    public int findPowerSize(int round) {
        int sq=1;
        while(true){
            sq*=2;
            if(sq>round){
                break;
            }
        }
        return sq;
    }
}
