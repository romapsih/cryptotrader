package com.after_sunrise.cryptocurrency.cryptotrader.service.bitfinex;

import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context.Key;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Request;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Service.CurrencyType;
import com.after_sunrise.cryptocurrency.cryptotrader.service.estimator.LastEstimator;
import com.after_sunrise.cryptocurrency.cryptotrader.service.estimator.MidEstimator;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * @author takanori.takase
 * @version 0.0.1
 */
public class BitfinexServiceTest {

    private Context context;

    private Request request;

    private Key key;

    @BeforeMethod
    public void setUp() {

        context = mock(Context.class);

        request = Request.builder().site("s").instrument("i").currentTime(Instant.now()).build();
        when(context.getInstrumentCurrency(Key.from(request))).thenReturn(CurrencyType.ETH);
        when(context.getFundingCurrency(Key.from(request))).thenReturn(CurrencyType.BTC);

        key = Key.builder().site("bitfinex").instrument("ethbtc")
                .timestamp(request.getCurrentTime()).build();

    }

    @Test
    public void testBitfinexLastEstimator() {

        BitfinexService.BitfinexLastEstimator target = new BitfinexService.BitfinexLastEstimator();

        assertEquals(target.get(), "BitfinexLastEstimator");

        assertTrue(LastEstimator.class.isInstance(target));

        assertEquals(target.getKey(context, request), key);

    }

    @Test
    public void testBitfinexMidEstimator() {

        BitfinexService.BitfinexMidEstimator target = new BitfinexService.BitfinexMidEstimator();

        assertEquals(target.get(), "BitfinexMidEstimator");

        assertTrue(MidEstimator.class.isInstance(target));

        assertEquals(target.getKey(context, request), key);

    }

}
