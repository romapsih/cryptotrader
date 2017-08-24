package com.after_sunrise.cryptocurrency.cryptotrader.service.estimator;

import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context.Key;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Estimator.Estimation;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Trade;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Trader.Request;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.apache.commons.lang3.math.NumberUtils.LONG_ONE;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

/**
 * @author takanori.takase
 * @version 0.0.1
 */
public class VwapEstimatorTest {

    private VwapEstimator target;

    private Context context;

    @BeforeMethod
    public void setUp() throws Exception {

        context = mock(Context.class);

        target = spy(new VwapEstimator());

    }

    @Test
    public void testGet() throws Exception {
        assertEquals(target.get(), target.getClass().getSimpleName());
    }

    @Test
    public void testGetNow() throws InterruptedException {

        Instant t1 = target.getNow();

        Thread.sleep(100L);

        assertNotEquals(target.getNow(), t1);

    }

    @Test
    public void testEstimate() throws Exception {

        Request request = Request.builder().build();
        Key key = Key.from(request);
        Instant now = Instant.now();
        doReturn(now).when(target).getNow();
        Instant from = now.minus(LONG_ONE, DAYS);

        Trade t1 = mock(Trade.class);
        Trade t2 = mock(Trade.class);
        Trade t3 = mock(Trade.class);
        Trade t4 = mock(Trade.class);
        Trade t5 = mock(Trade.class);
        Trade t6 = mock(Trade.class);
        Trade t7 = mock(Trade.class);
        when(t1.getPrice()).thenReturn(new BigDecimal("54.20735492"));
        when(t2.getPrice()).thenReturn(new BigDecimal("46.21598752"));
        when(t3.getPrice()).thenReturn(new BigDecimal("52.06059243"));
        when(t4.getPrice()).thenReturn(new BigDecimal("48.56048342"));
        when(t5.getPrice()).thenReturn(null);
        when(t6.getPrice()).thenReturn(new BigDecimal("12.345678901"));
        when(t7.getPrice()).thenReturn(new BigDecimal("23.456789012"));
        when(t1.getSize()).thenReturn(new BigDecimal("1"));
        when(t2.getSize()).thenReturn(new BigDecimal("2"));
        when(t3.getSize()).thenReturn(new BigDecimal("3"));
        when(t4.getSize()).thenReturn(new BigDecimal("4"));
        when(t5.getSize()).thenReturn(new BigDecimal("5"));
        when(t6.getSize()).thenReturn(null);
        when(t7.getSize()).thenReturn(new BigDecimal("-7"));

        when(context.listTrades(key, from)).thenReturn(asList(t1, t3, t5, t7, null, t2, t4, t6));
        Estimation estimation = target.estimate(context, request);
        assertEquals(estimation.getPrice(), new BigDecimal("49.706304093000"));
        assertEquals(estimation.getConfidence(), new BigDecimal("0.563121405835"));

        // One
        when(context.listTrades(key, from)).thenReturn(asList(t3));
        estimation = target.estimate(context, request);
        assertEquals(estimation.getPrice(), new BigDecimal("52.06059243"));
        assertEquals(estimation.getConfidence(), ONE);

        // Zero
        when(context.listTrades(key, from)).thenReturn(emptyList());
        estimation = target.estimate(context, request);
        assertEquals(estimation.getPrice(), null);
        assertEquals(estimation.getConfidence(), ZERO);

        // Null
        when(context.listTrades(key, from)).thenReturn(null);
        estimation = target.estimate(context, request);
        assertEquals(estimation.getPrice(), null);
        assertEquals(estimation.getConfidence(), ZERO);

    }

}