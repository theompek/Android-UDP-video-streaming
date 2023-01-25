package com.example.serverUDPImage;
/*********
 Author: Bekiaris Theofanis(Theompek)
 Date: 24/01/2023
 Version 1.0.1
 *********/

class codec_state
{
    short prevsample;
    short previndex;

    codec_state(){
        prevsample=0;
        previndex=0;
    }
};


/*****************************************************************************
 * ADPCMEncoder - ADPCM encoder routine *
 ******************************************************************************
 * Input Variables: *
 * signed long sample - 16-bit signed speech sample *
 * Return Variable: *
 * char - 8-bit number containing the 4-bit ADPCM code *
 * Source code reference: ttps://ww1.microchip.com/downloads/en/AppNotes/00643b.pdf
 *****************************************************************************/

public class ADPCME_Codec {
    /*Encoder*/
    private int diff_encoder;                         /* Difference between sample and predicted sample */
    private short step_encoder;                         /* Quantizer step size */
    private short predsample_encoder;               /* Output of ADPCM predictor */
    private int diffq_encoder;                    /* Dequantized predicted difference */
    private short index_encoder;                            /* Index into step size table */
    private codec_state state_encoder;                    /* Previous state of codec */

    /*Decoder*/
    private short step_decoder; /* Quantizer step size */
    private short predsample_decoder; /* Output of ADPCM predictor */
    private int diffq_decoder; /* Dequantized predicted difference */
    private short index_decoder; /* Index into step size table */
    private codec_state state_decoder;

    /* Table of index changes */
    private static final short[] IndexTable = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };

    /* Quantizer step size lookup table */
    private static final short[] StepSizeTable = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    ADPCME_Codec(){
        // Encoder init
        state_encoder = new codec_state();
        diff_encoder=0;
        step_encoder=0;
        predsample_encoder=0;
        diffq_encoder=0;
        index_encoder=0;
        state_encoder.prevsample = 0;
        state_encoder.previndex = 0;

        // Decoder init
        state_decoder = new codec_state();
        state_decoder.prevsample = 0;
        state_decoder.previndex = 0;
    }

    public byte encoder( short sample )
    {
        byte code; /* ADPCM output value */
        short tempstep; /* Temporary step size */
        /* Restore previous values of predicted sample and quantizer step size index */
        predsample_encoder = state_encoder.prevsample;
        index_encoder = state_encoder.previndex;
        step_encoder = StepSizeTable[index_encoder];
        /* Compute the difference between the actual sample (sample) and the the predicted sample (predsample_encoder) */
        diff_encoder = sample - predsample_encoder;
        if(diff_encoder >= 0){
            code = 0;
        }else{
            code = 8;
            diff_encoder = -diff_encoder;
        }
        /* Quantize the difference into the 4-bit ADPCM code using the quantizer step size */
        tempstep = step_encoder;
        if( diff_encoder >= tempstep )
        {
            code |= 4;
            diff_encoder -= tempstep;
        }
        tempstep >>= 1;
        if( diff_encoder >= tempstep )
        {
            code |= 2;
            diff_encoder -= tempstep;
        }
        tempstep >>= 1;
        if( diff_encoder >= tempstep )
            code |= 1;
  /* Inverse quantize the ADPCM code into a predicted difference
  using the quantizer step size
  */
        diffq_encoder = step_encoder >> 3;
        if( (code & 4) != 0)
            diffq_encoder += step_encoder;
        if( (code & 2) !=0)
            diffq_encoder += step_encoder >> 1;
        if( (code & 1) !=0)
            diffq_encoder += step_encoder >> 2;
        /* Fixed predictor computes new predicted sample by adding the old predicted sample to predicted difference  */
        if( (code & 8) !=0)
            predsample_encoder -= diffq_encoder;
        else
            predsample_encoder += diffq_encoder;
        /* Check for overflow of the new predicted sample */
        if( predsample_encoder > 32767 )
            predsample_encoder = 32767;
        else if( predsample_encoder < -32768 )
            predsample_encoder = -32768;
        /* Find new quantizer stepsize index by adding the old index to a table lookup using the ADPCM code */
        index_encoder += IndexTable[code];
        /* Check for overflow of the new quantizer step size index  */
        if( index_encoder < 0 )
            index_encoder = 0;
        if( index_encoder > 88 )
            index_encoder = 88;
        /* Save the predicted sample and quantizer step size index for next iteration  */
        state_encoder.prevsample = predsample_encoder;
        state_encoder.previndex = index_encoder;
        /* Return the new ADPCM code */
        return (byte)(code & 0x0f);
    }

    public short decoder(byte code)
    {
        /* Restore previous values of predicted sample and quantizer step size index */
        predsample_decoder = state_decoder.prevsample;
        index_decoder = state_decoder.previndex;
        /* Find quantizer step size from lookup table using index */
        step_decoder = StepSizeTable[index_decoder];
        /* Inverse quantize the ADPCM code into a difference using the quantizer step size */
        diffq_decoder = step_decoder >> 3;
        if( (code & 4) !=0)
            diffq_decoder += step_decoder;
        if( (code & 2) != 0)
            diffq_decoder += step_decoder >> 1;
        if( (code & 1) != 0)
            diffq_decoder += step_decoder >> 2;
        /* Add the difference to the predicted sample */
        if( (code & 8) != 0)
            predsample_decoder -= diffq_decoder;
        else
            predsample_decoder += diffq_decoder;
        /* Check for overflow of the new predicted sample */
        if( predsample_decoder > 32767 )
            predsample_decoder = 32767;
        else if( predsample_decoder < -32768 )
            predsample_decoder = -32768;
      /* Find new quantizer step size by adding the old index and a
      table lookup using the ADPCM code
      */
        index_decoder += IndexTable[code];
        /* Check for overflow of the new quantizer step size index
         */
        if( index_decoder < 0 )
            index_decoder = 0;
        if( index_decoder > 88 )
            index_decoder = 88;
      /* Save predicted sample and quantizer step size index for next
      iteration
      */
        state_decoder.prevsample = (short)predsample_decoder;
        state_decoder.previndex = index_decoder;
        /* Return the new speech sample */
        return( state_decoder.prevsample );
    }

}
