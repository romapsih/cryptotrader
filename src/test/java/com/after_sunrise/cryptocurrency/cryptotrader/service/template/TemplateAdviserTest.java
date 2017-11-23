package com.after_sunrise.cryptocurrency.cryptotrader.service.template;

import com.after_sunrise.cryptocurrency.cryptotrader.framework.Adviser.Advice;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context.Key;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Estimator.Estimation;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Order;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Order.Execution;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Request;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Trade;
import com.google.common.collect.Sets;
import org.mockito.invocation.InvocationOnMock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.after_sunrise.cryptocurrency.cryptotrader.framework.Service.CurrencyType.*;
import static com.after_sunrise.cryptocurrency.cryptotrader.service.bitflyer.BitflyerService.ID;
import static com.after_sunrise.cryptocurrency.cryptotrader.service.bitflyer.BitflyerService.ProductType.BTCJPY_MAT1WK;
import static com.after_sunrise.cryptocurrency.cryptotrader.service.bitflyer.BitflyerService.ProductType.BTC_JPY;
import static com.after_sunrise.cryptocurrency.cryptotrader.service.template.TemplateAdviser.SIGNUM_BUY;
import static com.after_sunrise.cryptocurrency.cryptotrader.service.template.TemplateAdviser.SIGNUM_SELL;
import static java.math.BigDecimal.*;
import static java.math.BigDecimal.valueOf;
import static java.time.Instant.now;
import static java.util.Collections.*;
import static org.apache.commons.lang3.math.NumberUtils.INTEGER_ZERO;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * @author takanori.takase
 * @version 0.0.1
 */
public class TemplateAdviserTest {

    private TemplateAdviser target;

    private Context context;

    private Request.RequestBuilder rBuilder;

    private Estimation.EstimationBuilder eBuilder;

    @BeforeMethod
    public void setUp() throws Exception {

        context = mock(Context.class);

        BiFunction<InvocationOnMock, BigDecimal, BigDecimal> f = (i, unit) -> {

            BigDecimal value = i.getArgumentAt(1, BigDecimal.class);

            RoundingMode mode = i.getArgumentAt(2, RoundingMode.class);

            if (value == null || mode == null) {
                return null;
            }

            BigDecimal units = value.divide(unit, INTEGER_ZERO, mode);

            return units.multiply(unit);

        };
        when(context.roundTickSize(any(), any(), any())).thenAnswer(i -> f.apply(i, new BigDecimal("0.0025")));
        when(context.roundLotSize(any(), any(), any())).thenAnswer(i -> f.apply(i, new BigDecimal("0.25")));

        rBuilder = Request.builder().site("s").instrument("i").targetTime(now());

        eBuilder = Estimation.builder().price(new BigDecimal("12345.6789")).confidence(new BigDecimal("0.5"));

        target = spy(new TemplateAdviser("test"));

    }

    @Test
    public void testGet() throws Exception {
        assertEquals(target.get(), "test");
    }

    @Test
    public void testAdvise() throws Exception {

        Request request = rBuilder.build();
        BigDecimal weighed = valueOf(100);
        BigDecimal basis = valueOf(200);
        BigDecimal bBasis = valueOf(201);
        BigDecimal sBasis = valueOf(202);
        BigDecimal bPrice = valueOf(301);
        BigDecimal sPrice = valueOf(302);
        BigDecimal bSize = valueOf(401);
        BigDecimal sSize = valueOf(402);

        Estimation estimation = eBuilder.build();
        doReturn(weighed).when(target).calculateWeighedPrice(context, request, estimation);
        doReturn(basis).when(target).calculateBasis(context, request);
        doReturn(bBasis).when(target).calculateBuyBasis(context, request, basis);
        doReturn(sBasis).when(target).calculateSellBasis(context, request, basis);

        doReturn(bPrice).when(target).calculateBuyLimitPrice(context, request, weighed, bBasis);
        doReturn(bSize).when(target).calculateBuyLimitSize(context, request, bPrice);
        doReturn(sPrice).when(target).calculateSellLimitPrice(context, request, weighed, sBasis);
        doReturn(sSize).when(target).calculateSellLimitSize(context, request, sPrice);

        Advice result = target.advise(context, request, estimation);
        assertEquals(result.getBuyLimitPrice(), bPrice);
        assertEquals(result.getBuyLimitSize(), bSize);
        assertEquals(result.getSellLimitPrice(), sPrice);
        assertEquals(result.getSellLimitSize(), sSize);

        // Invalid Request
        result = target.advise(context, Request.builder().build(), estimation);
        assertEquals(result.getBuyLimitPrice(), null);
        assertEquals(result.getBuyLimitSize().signum(), 0);
        assertEquals(result.getSellLimitPrice(), null);
        assertEquals(result.getSellLimitSize().signum(), 0);

        // Invalid Estimation
        result = target.advise(context, request, Estimation.builder().build());
        assertEquals(result.getBuyLimitPrice(), null);
        assertEquals(result.getBuyLimitSize().signum(), 0);
        assertEquals(result.getSellLimitPrice(), null);
        assertEquals(result.getSellLimitSize().signum(), 0);

    }

    @Test
    public void testCalculateBasis() {

        Request request = Request.builder().tradingSpread(new BigDecimal("0.0060")).build();
        when(context.getCommissionRate(Key.from(request))).thenReturn(new BigDecimal("0.0020"));

        // Static (null dynamic)
        doReturn(null).when(target).calculateDeviation(context, request);
        assertEquals(target.calculateBasis(context, request), new BigDecimal("0.0080"));

        // Static (with dynamic)
        doReturn(new BigDecimal("0.0001")).when(target).calculateDeviation(context, request);
        assertEquals(target.calculateBasis(context, request), new BigDecimal("0.0080"));

        // Dynamic
        doReturn(new BigDecimal("0.0090")).when(target).calculateDeviation(context, request);
        assertEquals(target.calculateBasis(context, request), new BigDecimal("0.0090"));

        // Null spread
        request = Request.builder().tradingSpread(null).build();
        when(context.getCommissionRate(Key.from(request))).thenReturn(new BigDecimal("0.0020"));
        assertNull(target.calculateBasis(context, request));

        // Null commission
        request = Request.builder().tradingSpread(new BigDecimal("0.0060")).build();
        when(context.getCommissionRate(Key.from(request))).thenReturn(null);
        assertNull(target.calculateBasis(context, request));

    }

    @Test
    public void testCalculateDeviation() {

        Instant t0 = Instant.ofEpochMilli(10000);
        Instant t1 = Instant.ofEpochMilli(10100);
        Request request = Request.builder().currentTime(t0).targetTime(t1)
                .tradingSigma(TEN).tradingSamples(60).build();

        List<Trade> trades = singletonList(mock(Trade.class));
        when(context.listTrades(any(), eq(Instant.ofEpochMilli(3900)))).thenReturn(trades);

        Duration interval = Duration.ofMillis(100);
        NavigableMap<Instant, BigDecimal> prices = new TreeMap<>(singletonMap(t0, TEN));
        doReturn(prices).when(target).collapsePrices(trades, interval, Instant.ofEpochMilli(4000), t0);

        NavigableMap<Instant, BigDecimal> returns = new TreeMap<>();
        returns.put(Instant.ofEpochMilli(10), null);
        returns.put(Instant.ofEpochMilli(11), new BigDecimal("-0.0092"));
        returns.put(Instant.ofEpochMilli(12), new BigDecimal("+0.0027"));
        returns.put(Instant.ofEpochMilli(13), new BigDecimal("-0.0141"));
        returns.put(Instant.ofEpochMilli(14), new BigDecimal("+0.0036"));
        returns.put(Instant.ofEpochMilli(15), new BigDecimal("+0.0071"));
        returns.put(Instant.ofEpochMilli(16), new BigDecimal("-0.0027"));
        returns.put(Instant.ofEpochMilli(17), new BigDecimal("-0.0105"));
        returns.put(Instant.ofEpochMilli(18), new BigDecimal("-0.0068"));
        returns.put(Instant.ofEpochMilli(19), new BigDecimal("-0.0050"));
        returns.put(Instant.ofEpochMilli(20), new BigDecimal("+0.0011"));
        returns.put(Instant.ofEpochMilli(21), new BigDecimal("+0.0034"));
        returns.put(Instant.ofEpochMilli(22), new BigDecimal("+0.0007"));
        returns.put(Instant.ofEpochMilli(23), new BigDecimal("-0.0001"));
        returns.put(Instant.ofEpochMilli(24), new BigDecimal("+0.0103"));
        returns.put(Instant.ofEpochMilli(25), new BigDecimal("+0.0136"));
        returns.put(Instant.ofEpochMilli(26), new BigDecimal("+0.0107"));
        returns.put(Instant.ofEpochMilli(27), new BigDecimal("+0.0110"));
        returns.put(Instant.ofEpochMilli(28), new BigDecimal("-0.0019"));
        returns.put(Instant.ofEpochMilli(29), new BigDecimal("-0.0029"));
        returns.put(Instant.ofEpochMilli(30), new BigDecimal("+0.0059"));
        returns.put(Instant.ofEpochMilli(31), null);

        // Valid
        doReturn(returns).when(target).calculateReturns(prices);
        assertEquals(target.calculateDeviation(context, request), new BigDecimal("0.0774591907"));

        // Zero variance
        new ArrayList<>(returns.keySet()).forEach(k -> returns.put(k, new BigDecimal("-0.01")));
        assertEquals(target.calculateDeviation(context, request), new BigDecimal("0.0100000000"));

        // Empty
        doReturn(new TreeMap<>()).when(target).calculateReturns(prices);
        assertEquals(target.calculateDeviation(context, request), null);

    }

    @Test
    public void testCalculatePositionRatio_Cash() {

        Request request = rBuilder.fundingOffset(new BigDecimal("-0.50")).build();
        Request aversion = rBuilder.tradingAversion(new BigDecimal("1.5")).build();
        Request ignore = rBuilder.tradingAversion(new BigDecimal("0.0")).build();
        Key key = Key.from(request);
        when(context.isMarginable(key)).thenReturn(null);

        // Net-Long
        // Fund = 9,800 : Structure = 5 * 2345 = 11,725
        // (11725 - 9800) / (11725 + 9800) = 0.0894308943089...
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("0.1788617886"));

        // Net-Long (aversion)
        // Fund = 9,800 : Structure = 5 * 2345 = 11,725
        // (11725 - 9800) / (11725 + 9800) = 0.0894308943089...
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, aversion), new BigDecimal("0.2682926829"));

        // Zero aversion
        assertEquals(target.calculatePositionRatio(context, ignore), ZERO);

        // Long-only
        // Fund = 0 : Structure = 5 * 2345 = 11,725
        // (11725 - 0) / (11725 + 0) = 1
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("0"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("2.0000000000"));

        // Net-Short
        // Fund = 12,345 : Structure = 5 * 2,345 = 11,725
        // (11,725 - 12,345) / (11,725 + 12,345) = -0.025758205234732...
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("24690"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("-0.0515164105"));

        // Net-Short (aversion)
        // Fund = 12,345 : Structure = 5 * 2,345 = 11,725
        // (11,725 - 12,345) / (11,725 + 12,345) = -0.025758205234732...
        // Aversion = 1.5
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("24690"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, aversion), new BigDecimal("-0.0772746158"));

        // Short-Only
        // Fund = 12,345 : Structure = 0
        // (0 - 12,345) / (0 + 12,345) = -1
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("24690"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("0"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("-2.0000000000"));

        // Zero Asset
        when(context.getFundingPosition(key)).thenReturn(ZERO);
        when(context.getInstrumentPosition(key)).thenReturn(ZERO);
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertEquals(target.calculatePositionRatio(context, request), ZERO);

        // Null Funding
        when(context.getFundingPosition(key)).thenReturn(null);
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("0"));
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertNull(target.calculatePositionRatio(context, request));

        // Null Structure
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("24690"));
        when(context.getInstrumentPosition(key)).thenReturn(null);
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        assertNull(target.calculatePositionRatio(context, request));

        // Null Mid Price
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("24690"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("0"));
        when(context.getMidPrice(key)).thenReturn(null);
        assertNull(target.calculatePositionRatio(context, request));

    }

    @Test
    public void testCalculatePositionRatio_Margin() {

        Request request = rBuilder.fundingOffset(new BigDecimal("-0.50")).build();
        Request aversion = rBuilder.tradingAversion(new BigDecimal("1.5")).build();
        Request ignore = rBuilder.tradingAversion(new BigDecimal("0.0")).build();
        Key key = Key.from(request);
        when(context.isMarginable(key)).thenReturn(true);

        // Long (2 * 2345 * 3  / 9800 = 0.71785714285714..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("3"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("1.4357142857"));

        // Leveraged Long (2 * 2345 * 5 / 9800 = 1.19642857142857..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("2.3928571429"));

        // Aversion Leveraged Long (2 * 2345 * 5 / 9800 = 1.19642857142857..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("5"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, aversion), new BigDecimal("3.5892857144"));

        // Zero aversion
        assertEquals(target.calculatePositionRatio(context, ignore), ZERO);

        // Short (2 * 2345 * -3 / 9800 = -0.71785714285714..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("-3"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("-1.4357142857"));

        // Leveraged Short (2 * 2345 * -5 / 9800 = -1.19642857142857..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("-5"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, request), new BigDecimal("-2.3928571429"));

        // Aversion Leveraged Short (2 * 2345 * -5 / 9800 = -1.19642857142857..)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("-5"));
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, aversion), new BigDecimal("-3.5892857144"));

        // Flat (2345 * 0 / 9800 = 0)
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(ZERO);
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertEquals(target.calculatePositionRatio(context, request).signum(), 0);

        // Zero Funding
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(ZERO);
        when(context.getFundingPosition(key)).thenReturn(ZERO);
        assertEquals(target.calculatePositionRatio(context, request).signum(), 0);

        // Null Funding
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(ZERO);
        when(context.getFundingPosition(key)).thenReturn(null);
        assertNull(target.calculatePositionRatio(context, request));

        // Null Structure
        when(context.getMidPrice(key)).thenReturn(new BigDecimal("2345"));
        when(context.getInstrumentPosition(key)).thenReturn(null);
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertNull(target.calculatePositionRatio(context, request));

        // Null Mid Price
        when(context.getMidPrice(key)).thenReturn(null);
        when(context.getInstrumentPosition(key)).thenReturn(ZERO);
        when(context.getFundingPosition(key)).thenReturn(new BigDecimal("19600"));
        assertNull(target.calculatePositionRatio(context, request));

    }

    @Test
    public void testCalculateFundingOffset() {

        BigDecimal offset = new BigDecimal("0.0000");

        Request.RequestBuilder builder = Request.builder().instrument(BTC_JPY.name()).fundingOffset(offset)
                .fundingMultiplierProducts(singletonMap("*" + ID, singleton(BTCJPY_MAT1WK.name())))
                .fundingPositiveMultiplier(valueOf(100)).fundingNegativeMultiplier(valueOf(95));

        Request r1 = builder.build();

        Request r2 = builder.fundingPositiveThreshold(valueOf(+21)).fundingNegativeThreshold(valueOf(-21)).build();

        Request r3 = Request.builder().site(ID).instrument(BTCJPY_MAT1WK.name()).build();

        Stream.of(
                new SimpleEntry<>(new BigDecimal("845000"), new BigDecimal("30.0000000000")), // +30%
                new SimpleEntry<>(new BigDecimal("780000"), new BigDecimal("20.0000000000")), // +20%
                new SimpleEntry<>(new BigDecimal("715000"), new BigDecimal("10.0000000000")), // +10%
                new SimpleEntry<>(new BigDecimal("663000"), new BigDecimal("2.0000000000")), // +2%
                new SimpleEntry<>(new BigDecimal("656500"), new BigDecimal("1.0000000000")), // +1%
                new SimpleEntry<>(new BigDecimal("650000"), new BigDecimal("0.0000000000")), // 0%
                new SimpleEntry<>(new BigDecimal("643500"), new BigDecimal("-0.9500000000")), // -1%
                new SimpleEntry<>(new BigDecimal("637000"), new BigDecimal("-1.9000000000")), // -2%
                new SimpleEntry<>(new BigDecimal("585000"), new BigDecimal("-9.5000000000")), // -10%
                new SimpleEntry<>(new BigDecimal("520000"), new BigDecimal("-19.0000000000")), // -20%
                new SimpleEntry<>(new BigDecimal("455000"), new BigDecimal("-28.5000000000")) // -30%
        ).forEach(e -> {
            String key = e.getKey().toPlainString();
            BigDecimal val = e.getValue();
            when(context.getMidPrice(Key.from(r1))).thenReturn(valueOf(650000));
            when(context.getMidPrice(Key.from(r2))).thenReturn(valueOf(650000));
            when(context.getMidPrice(Key.from(r3))).thenReturn(e.getKey());
            assertEquals(target.calculateFundingOffset(context, r1), val, key);
            assertEquals(target.calculateFundingOffset(context, r2), val.min(valueOf(+21)).max(valueOf(-21)), key);
            assertEquals(target.calculateFundingOffset(context, r3), null, key);
        });

        // Zero 1
        when(context.getMidPrice(Key.from(r1))).thenReturn(new BigDecimal("0.0"));
        when(context.getMidPrice(Key.from(r2))).thenReturn(new BigDecimal("0.0"));
        when(context.getMidPrice(Key.from(r3))).thenReturn(valueOf(650000));
        assertEquals(target.calculateFundingOffset(context, r1), offset);
        assertEquals(target.calculateFundingOffset(context, r2), offset);
        assertEquals(target.calculateFundingOffset(context, r3), null);

        // Zero 2
        when(context.getMidPrice(Key.from(r1))).thenReturn(valueOf(650000));
        when(context.getMidPrice(Key.from(r2))).thenReturn(valueOf(650000));
        when(context.getMidPrice(Key.from(r3))).thenReturn(new BigDecimal("0.0"));
        assertEquals(target.calculateFundingOffset(context, r1), offset);
        assertEquals(target.calculateFundingOffset(context, r2), offset);
        assertEquals(target.calculateFundingOffset(context, r3), null);

        // Null 1
        when(context.getMidPrice(Key.from(r1))).thenReturn(null);
        when(context.getMidPrice(Key.from(r2))).thenReturn(null);
        when(context.getMidPrice(Key.from(r3))).thenReturn(valueOf(650000));
        assertEquals(target.calculateFundingOffset(context, r1), offset);
        assertEquals(target.calculateFundingOffset(context, r2), offset);
        assertEquals(target.calculateFundingOffset(context, r3), null);

        // Null 2
        when(context.getMidPrice(Key.from(r1))).thenReturn(valueOf(650000));
        when(context.getMidPrice(Key.from(r2))).thenReturn(valueOf(650000));
        when(context.getMidPrice(Key.from(r3))).thenReturn(null);
        assertEquals(target.calculateFundingOffset(context, r1), offset);
        assertEquals(target.calculateFundingOffset(context, r2), offset);
        assertEquals(target.calculateFundingOffset(context, r3), null);

    }

    @Test
    public void testCalculateRecentPrice() {

        Instant now = Instant.ofEpochMilli(150);
        Duration duration = Duration.ofMillis(10);
        Request request = rBuilder.currentTime(now).tradingDuration(duration).build();

        List<Execution> executions = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            Execution exec = mock(Execution.class);
            when(exec.getTime()).thenReturn(Instant.ofEpochMilli(135 + i)); // 6 <= i <= 19
            when(exec.getPrice()).thenReturn(BigDecimal.valueOf(100 + i));
            when(exec.getSize()).thenReturn(BigDecimal.valueOf((10000 + i) * (i % 2 == 0 ? 1 : -1)));
            executions.add(exec);
        }

        when(context.listExecutions(Key.from(request))).thenReturn(executions);
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_BUY), new BigDecimal("118"));
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_SELL), new BigDecimal("107"));

        // Invalid Price
        when(executions.get(18).getPrice()).thenReturn(null);
        when(executions.get(7).getPrice()).thenReturn(ZERO);
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_BUY), new BigDecimal("116"));
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_SELL), new BigDecimal("109"));

        // Invalid Size
        when(executions.get(16).getSize()).thenReturn(ZERO);
        when(executions.get(9).getSize()).thenReturn(null);
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_BUY), new BigDecimal("114"));
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_SELL), new BigDecimal("111"));

        // Invalid Time
        when(executions.get(14).getTime()).thenReturn(null);
        when(executions.get(11).getTime()).thenReturn(Instant.ofEpochMilli(1));
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_BUY), new BigDecimal("112"));
        assertEquals(target.calculateRecentPrice(context, request, SIGNUM_SELL), new BigDecimal("113"));

        // No Execution
        when(context.listExecutions(Key.from(request))).thenReturn(null);
        assertNull(target.calculateRecentPrice(context, request, SIGNUM_BUY));
        assertNull(target.calculateRecentPrice(context, request, SIGNUM_SELL));

        // Zero Duration
        reset(context);
        request = rBuilder.tradingDuration(Duration.ZERO).build();
        assertNull(target.calculateRecentPrice(context, request, SIGNUM_BUY));
        assertNull(target.calculateRecentPrice(context, request, SIGNUM_SELL));
        verifyNoMoreInteractions(context);

    }

    @Test
    public void testCalculateBuyLossBasis() {

        Request request = rBuilder.build();
        Key key = Key.from(request);

        // 10 bps (no loss)
        BigDecimal market = new BigDecimal("445000");
        BigDecimal recent = new BigDecimal("444000");
        doReturn(market).when(context).getBestBidPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request).signum(), 0);

        // loss = (446000 - 445000) / 446000 = 0.002242152466368...
        market = new BigDecimal("445000");
        recent = new BigDecimal("446000");
        doReturn(market).when(context).getBestBidPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request), new BigDecimal("0.0022421525"));

        // With aversion
        request = rBuilder.tradingAversion(new BigDecimal("3")).build();
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request), new BigDecimal("0.0067264575"));

        // Null market
        doReturn(null).when(context).getBestBidPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request), ZERO);

        // Null latest
        doReturn(market).when(context).getBestBidPrice(key);
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request), ZERO);

        // Zero latest
        doReturn(market).when(context).getBestBidPrice(key);
        doReturn(new BigDecimal("0.0")).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateBuyLossBasis(context, request), ZERO);

    }

    @Test
    public void testCalculateBuyBasis() {

        Request request = rBuilder.tradingSpreadBid(new BigDecimal("-0.0005")).build();
        BigDecimal base = new BigDecimal("0.0010");

        // (0.0010 - 0.0005) * 1 + 0.012 = 0.0125
        doReturn(new BigDecimal("-0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateBuyLossBasis(context, request);
        assertEquals(target.calculateBuyBasis(context, request, base), new BigDecimal("0.0125"));

        // (0.0010 - 0.0005) * 1.987 + 0.012 = 0.0129935
        doReturn(new BigDecimal("+0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateBuyLossBasis(context, request);
        assertEquals(target.calculateBuyBasis(context, request, base), new BigDecimal("0.0129935"));

        // (0.0010 - 0.0005) * 1 + 0.012 = 0.0125
        doReturn(null).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateBuyLossBasis(context, request);
        assertEquals(target.calculateBuyBasis(context, request, base), new BigDecimal("0.0125"));

        // (0.0010 - 0.0005) * 1.987 + 0.000 = 0.0009935
        doReturn(new BigDecimal("+0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(null).when(target).calculateBuyLossBasis(context, request);
        assertEquals(target.calculateBuyBasis(context, request, base), new BigDecimal("0.0009935"));

        assertEquals(target.calculateBuyBasis(context, request, null), null);

    }

    @Test
    public void testCalculateSellLossBasis() {

        Request request = rBuilder.build();
        Key key = Key.from(request);

        // 10 bps (no loss)
        BigDecimal market = new BigDecimal("444000");
        BigDecimal recent = new BigDecimal("445000");
        doReturn(market).when(context).getBestAskPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request).signum(), 0);

        // loss = (446000 - 445000) / 445000 = 0.002247191011236...
        market = new BigDecimal("446000");
        recent = new BigDecimal("445000");
        doReturn(market).when(context).getBestAskPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request), new BigDecimal("0.0022471911"));

        // With aversion
        request = rBuilder.tradingAversion(new BigDecimal("3")).build();
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request), new BigDecimal("0.0067415733"));

        // Null market
        doReturn(null).when(context).getBestAskPrice(key);
        doReturn(recent).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request), ZERO);

        // Null latest
        doReturn(market).when(context).getBestAskPrice(key);
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request), ZERO);

        // Zero latest
        doReturn(market).when(context).getBestAskPrice(key);
        doReturn(new BigDecimal("0.0")).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateSellLossBasis(context, request), ZERO);

    }

    @Test
    public void testCalculateSellBasis() {

        Request request = rBuilder.tradingSpreadAsk(new BigDecimal("-0.0005")).build();
        BigDecimal base = new BigDecimal("0.0010");

        // (0.0010 - 0.0005) * 1 + 0.012 = 0.0125
        doReturn(new BigDecimal("+0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateSellLossBasis(context, request);
        assertEquals(target.calculateSellBasis(context, request, base), new BigDecimal("0.0125"));

        // (0.0010 - 0.0005) * 1.987 + 0.012 = 0.0129935
        doReturn(new BigDecimal("-0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateSellLossBasis(context, request);
        assertEquals(target.calculateSellBasis(context, request, base), new BigDecimal("0.0129935"));

        // (0.0010 - 0.0005) * 1 + 0.012 = 0.0125
        doReturn(null).when(target).calculatePositionRatio(context, request);
        doReturn(new BigDecimal("+0.012")).when(target).calculateSellLossBasis(context, request);
        assertEquals(target.calculateSellBasis(context, request, base), new BigDecimal("0.0125"));

        // (0.0010 - 0.0005) * 1.987 + 0.000 = 0.0009935
        doReturn(new BigDecimal("-0.987")).when(target).calculatePositionRatio(context, request);
        doReturn(null).when(target).calculateSellLossBasis(context, request);
        assertEquals(target.calculateSellBasis(context, request, base), new BigDecimal("0.0009935"));

        assertEquals(target.calculateSellBasis(context, request, null), null);

    }

    @Test
    public void testCalculateBuyBoundaryPrice() {

        Request request = rBuilder.build();
        Key key = Key.from(request);
        doReturn(new BigDecimal("0.0001")).when(target).calculateBasis(context, request);

        // Normal
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("16000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("15000.0025"));

        // Equal
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("14999.9975"));

        // Inverse
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("16000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("14999.9975"));

        // Null Bid
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(null);
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("14999.9975"));

        // Null Ask
        when(context.getBestAskPrice(key)).thenReturn(null);
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), null);

        // Losing unwind
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("16000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(valueOf(14500)).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("14497.1000"));

        // Already at BBO
        Order order = mock(Order.class);
        when(order.getOrderPrice()).thenReturn(valueOf(15000));
        when(order.getOrderQuantity()).thenReturn(ONE);
        when(context.listActiveOrders(key)).thenReturn(singletonList(order));
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("16000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_SELL);
        assertEquals(target.calculateBuyBoundaryPrice(context, request), new BigDecimal("15000.0000"));

    }

    @Test
    public void testCalculateSellBoundaryPrice() {

        Request request = rBuilder.build();
        Key key = Key.from(request);
        doReturn(new BigDecimal("0.0001")).when(target).calculateBasis(context, request);

        // Normal
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("14000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("14999.9975"));

        // Equal
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("15000.0025"));

        // Inverse
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("16000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("16000.0025"));

        // Null Bid
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(null);
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), null);

        // Null Ask
        when(context.getBestAskPrice(key)).thenReturn(null);
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("15000.0025"));

        // Losing unwind
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("14000.0000"));
        doReturn(valueOf(15500)).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("15503.1000"));

        // Already at BBO
        Order order = mock(Order.class);
        when(order.getOrderPrice()).thenReturn(valueOf(15000));
        when(order.getOrderQuantity()).thenReturn(ONE.negate());
        when(context.listActiveOrders(key)).thenReturn(singletonList(order));
        when(context.getBestAskPrice(key)).thenReturn(new BigDecimal("15000.0000"));
        when(context.getBestBidPrice(key)).thenReturn(new BigDecimal("14000.0000"));
        doReturn(null).when(target).calculateRecentPrice(context, request, SIGNUM_BUY);
        assertEquals(target.calculateSellBoundaryPrice(context, request), new BigDecimal("15000.0000"));

    }

    @Test
    public void testCalculateBuyLimitPrice() throws Exception {

        Request request = rBuilder.build();

        BigDecimal weighed = new BigDecimal("12347.7777");
        BigDecimal basis = new BigDecimal("0.0096");

        // Target : 12347.7777 * (1 - 96 bps) = 12229.23903408
        // Cross : min(12229.23903408, 20000)
        // Rounded : 12229.2375
        doReturn(new BigDecimal("20000")).when(target).calculateBuyBoundaryPrice(context, request);
        BigDecimal result = target.calculateBuyLimitPrice(context, request, weighed, basis);
        assertEquals(result, new BigDecimal("12229.2375"));

        // Cross Protected
        doReturn(new BigDecimal("9999.9975")).when(target).calculateBuyBoundaryPrice(context, request);
        result = target.calculateBuyLimitPrice(context, request, weighed, basis);
        assertEquals(result, new BigDecimal("9999.9975"));

        // Invalid Input
        assertNull(target.calculateBuyLimitPrice(context, request, null, basis));
        assertNull(target.calculateBuyLimitPrice(context, request, weighed, null));

        // Null Bound
        doReturn(null).when(target).calculateBuyBoundaryPrice(context, request);
        assertNull(target.calculateBuyLimitPrice(context, request, weighed, basis));

    }

    @Test
    public void testCalculateSellLimitPrice() throws Exception {

        Request request = rBuilder.build();

        BigDecimal weighed = new BigDecimal("12347.7777");
        BigDecimal basis = new BigDecimal("0.0096");

        // Target : 12347.7777 * (1 + 96 bps) = 12466.31636592
        // Cross : max(12466.31636592, 10000)
        // Rounded : 12466.3175
        doReturn(new BigDecimal("10000")).when(target).calculateSellBoundaryPrice(context, request);
        BigDecimal result = target.calculateSellLimitPrice(context, request, weighed, basis);
        assertEquals(result, new BigDecimal("12466.3175"));

        // Cross Protected
        doReturn(new BigDecimal("20000.0025")).when(target).calculateSellBoundaryPrice(context, request);
        result = target.calculateSellLimitPrice(context, request, weighed, basis);
        assertEquals(result, new BigDecimal("20000.0025"));

        // Invalid Input
        assertNull(target.calculateSellLimitPrice(context, request, null, basis));
        assertNull(target.calculateSellLimitPrice(context, request, weighed, null));

        // Null Bound
        doReturn(null).when(target).calculateSellBoundaryPrice(context, request);
        assertNull(target.calculateSellLimitPrice(context, request, weighed, basis));

    }

    @Test
    public void testCalculateTradingExposure() {

        Request request = rBuilder.tradingExposure(new BigDecimal("0.10"))
                .fundingOffset(new BigDecimal("0.00")).build();

        Stream.of(
                new SimpleEntry<>(new BigDecimal("+0.00"), new BigDecimal("0.1000000000")),
                new SimpleEntry<>(new BigDecimal("+0.50"), new BigDecimal("0.0816496581")),
                new SimpleEntry<>(new BigDecimal("+1.00"), new BigDecimal("0.0707106781")),
                new SimpleEntry<>(new BigDecimal("+2.00"), new BigDecimal("0.0577350269")),
                new SimpleEntry<>(new BigDecimal("+4.00"), new BigDecimal("0.0447213595")),
                new SimpleEntry<>(new BigDecimal("+8.00"), new BigDecimal("0.0333333333")),
                new SimpleEntry<>(new BigDecimal("16.00"), new BigDecimal("0.0242535625")),
                new SimpleEntry<>(new BigDecimal("-0.10"), new BigDecimal("0.1054092553")),
                new SimpleEntry<>(new BigDecimal("-0.20"), new BigDecimal("0.1118033989")),
                new SimpleEntry<>(new BigDecimal("-0.40"), new BigDecimal("0.1290994449")),
                new SimpleEntry<>(new BigDecimal("-0.80"), new BigDecimal("0.2236067977")),
                new SimpleEntry<>(new BigDecimal("-0.95"), new BigDecimal("0.4472135955")),
                new SimpleEntry<>(new BigDecimal("-0.98"), new BigDecimal("0.7071067812")),
                new SimpleEntry<>(new BigDecimal("-0.99"), new BigDecimal("1.0000000000")),
                new SimpleEntry<>(new BigDecimal("-1.00"), new BigDecimal("1")),
                new SimpleEntry<>(new BigDecimal("-2.00"), new BigDecimal("1")),
                new SimpleEntry<>(null, null)
        ).forEach(entry -> {
            doReturn(entry.getKey()).when(target).calculateFundingOffset(context, request);
            BigDecimal result = target.calculateTradingExposure(context, request);
            assertEquals(result, entry.getValue(), "Key : " + entry.getKey());
        });

    }

    @Test
    public void testCalculateFundingExposureSize() {

        Request.RequestBuilder builder = rBuilder.fundingOffset(new BigDecimal("-0.50"));
        when(context.getFundingPosition(any())).thenReturn(new BigDecimal("18000"));
        when(context.getCommissionRate(any())).thenReturn(new BigDecimal("0.0020"));
        doReturn(new BigDecimal("0.10")).when(target).calculateTradingExposure(eq(context), any());
        BigDecimal price = new BigDecimal("123.4567");

        // Exposed = Fund * Exposure = 900
        // Exposed / Price = 900 / 123.4567 = 7.290005322...
        BigDecimal expect = new BigDecimal("7.290005321700");
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), price), expect);

        // Leveraged
        // Exposed = Fund * (1 + 5) * Exposure = 10800
        // Exposed / Price = 10800 / 123.4567 = 87.4800638...
        builder = builder.fundingOffset(BigDecimal.valueOf(5));
        expect = new BigDecimal("87.480063860450");
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), price), expect);

        // Leveraged (Over)
        // Exposed = Fund * (1 + 100) * Exposure = 180000
        // Exposed / Price = 180000 / 123.4567 = 1,458.00106434...
        // Actual Fund / Price = 18000 / 123.4567 = 145.800106434077697
        // Commission Adjusted = 145.800106434077697 * (1 - 0.0020) = 145.508506221209542
        builder = builder.fundingOffset(BigDecimal.valueOf(100));
        expect = new BigDecimal("145.5085062212");
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), price), expect);

        // Invalid price
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), null), ZERO);
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), ZERO), ZERO);

        // Invalid Fund
        when(context.getFundingPosition(any())).thenReturn(null);
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), price), ZERO);
        when(context.getFundingPosition(any())).thenReturn(new BigDecimal("18000"));

        // Hedge
        builder = builder.hedgeProducts(singletonMap("foo", singleton("bar")));
        assertEquals(target.calculateFundingExposureSize(context, builder.build(), price), ZERO);

    }

    @Test
    public void testCalculateInstrumentExposureSize() {

        Request request = rBuilder.build();
        Key key = Key.from(request);
        doReturn(new BigDecimal("0.10")).when(target).calculateTradingExposure(context, request);

        // Exposed = Instrument * Exposure = 900
        BigDecimal expect = new BigDecimal("900.000000000000");
        when(context.getInstrumentPosition(key)).thenReturn(new BigDecimal("9000"));
        when(context.getInstrumentCurrency(any())).thenReturn(BTC);
        when(context.getConversionPrice(key, BTC)).thenReturn(ONE);
        when(context.getCommissionRate(key)).thenReturn(null);
        assertEquals(target.calculateInstrumentExposureSize(context, request), expect);

        // Less than commission
        when(context.getCommissionRate(key)).thenReturn(new BigDecimal("0.05"));
        assertEquals(target.calculateInstrumentExposureSize(context, request), expect);

        // More than commission
        expect = new BigDecimal("720.000000000000");
        when(context.getCommissionRate(key)).thenReturn(new BigDecimal("0.92"));
        assertEquals(target.calculateInstrumentExposureSize(context, request), expect);

        // Invalid Fund
        when(context.getInstrumentPosition(key)).thenReturn(null);
        assertEquals(target.calculateInstrumentExposureSize(context, request), ZERO);

    }

    @Test
    public void testCalculateInstrumentExposureSize_Hedge() {

        Map<String, Set<String>> hedgeProducts = new HashMap<>();
        hedgeProducts.put("a", Sets.newHashSet("XBTC", "XETH"));
        hedgeProducts.put("b", Sets.newHashSet("XBCH"));

        Request request = rBuilder.hedgeProducts(hedgeProducts).build();
        Key keySen = Key.from(request);
        Key keyBtc = Key.builder().site("a").instrument("XBTC").build();
        Key keyEth = Key.builder().site("a").instrument("XETH").build();
        Key keyBch = Key.builder().site("b").instrument("XBCH").build();

        Runnable initializer = () -> {

            when(context.getInstrumentCurrency(any())).thenReturn(null);
            when(context.getInstrumentCurrency(keySen)).thenReturn(JPY);
            when(context.getInstrumentCurrency(keyBtc)).thenReturn(BTC);
            when(context.getInstrumentCurrency(keyEth)).thenReturn(ETH);
            when(context.getInstrumentCurrency(keyBch)).thenReturn(BCH);

            // 1 JPY = ???
            when(context.getConversionPrice(keySen, JPY)).thenReturn(new BigDecimal("100"));
            when(context.getConversionPrice(keyBtc, JPY)).thenReturn(new BigDecimal("0.04"));
            when(context.getConversionPrice(keyEth, JPY)).thenReturn(new BigDecimal("0.16"));
            when(context.getConversionPrice(keyBch, JPY)).thenReturn(new BigDecimal("0.64"));

            when(context.getInstrumentPosition(any())).thenReturn(null);
            when(context.getInstrumentPosition(keyBtc)).thenReturn(valueOf(16)); // = JPY 400
            when(context.getInstrumentPosition(keyEth)).thenReturn(valueOf(32)); // = JPY 200
            when(context.getInstrumentPosition(keyBch)).thenReturn(valueOf(64)); // = JPY 100

            doReturn(new BigDecimal("0.25")).when(target).calculateTradingExposure(context, request);

        };

        // = (400 + 200 + 100) = JPY 700 = 70000 SEN
        // -> 25% Exposure = 17500 SEN
        initializer.run();
        assertEquals(target.calculateInstrumentExposureSize(context, request), new BigDecimal("17500.000000000000"));

        // No price
        initializer.run();
        when(context.getConversionPrice(any(), any())).thenReturn(null);
        assertEquals(target.calculateInstrumentExposureSize(context, request), new BigDecimal("0"));

        // No position
        initializer.run();
        when(context.getInstrumentCurrency(any())).thenReturn(null);
        assertEquals(target.calculateInstrumentExposureSize(context, request), new BigDecimal("0"));

    }

    @Test
    public void testCalculateBuyLimitSize_Cash() throws Exception {

        Request request = rBuilder.build();
        Key key = Key.from(request);
        BigDecimal price = new BigDecimal("123.45");
        when(context.isMarginable(key)).thenReturn(null);

        // Net Short 1 (123.00 -> 123.00)
        doReturn(new BigDecimal("123")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("0")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("123.00"));

        // Net Short 2 (98.40 -> 98.50)
        doReturn(new BigDecimal("98.4")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("24.6")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("98.50"));

        // Equal (61.50 -> 61.50)
        doReturn(new BigDecimal("61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 1 (17.22 -> 17.25)
        doReturn(new BigDecimal("24.6")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("98.4")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("17.25"));

        // Net Long 2 (0.00 -> 0.00)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("0.00"));

    }

    @Test
    public void testCalculateBuyLimitSize_Margin() throws Exception {

        Request request = rBuilder.tradingMinimum(ZERO).build();
        Key key = Key.from(request);
        BigDecimal price = new BigDecimal("123.45");
        when(context.isMarginable(key)).thenReturn(true);

        // Net Short 1 (61.50 -> 61.50)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Short 2 (61.50 -> 61.50)
        doReturn(new BigDecimal("+61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Short 3 (61.50 -> 61.50)
        doReturn(new BigDecimal("+73.8")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-49.2")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("61.50"));

        // Equal (61.50 -> 61.50)
        doReturn(new BigDecimal("123")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("0")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 1 (12.30 -> 12.25)
        doReturn(new BigDecimal("73.8")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("49.2")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("12.25"));

        // Net Long 2 (0.00 -> 0.00)
        doReturn(new BigDecimal("61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("0.00"));

        // Net Long 3 (0.00 -> 0.00)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateBuyLimitSize(context, request, price), new BigDecimal("0.00"));

    }

    @Test
    public void testCalculateSellLimitSize_Cash() throws Exception {

        Request request = rBuilder.tradingMinimum(ZERO).build();
        Key key = Key.from(request);
        BigDecimal price = new BigDecimal("123.45");
        when(context.isMarginable(key)).thenReturn(null);

        // Net Short 1 (0.00 -> 0.00)
        doReturn(new BigDecimal("123")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("0")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("0.00"));

        // Net Short 2 (17.22 -> 17.25)
        doReturn(new BigDecimal("98.4")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("24.6")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("17.25"));

        // Equal (61.50 -> 61.50)
        doReturn(new BigDecimal("61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 1 (98.40 -> 98.50)
        doReturn(new BigDecimal("24.6")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("98.4")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("98.50"));

        // Net Long 2 (123.00 -> 123.00)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("123.00"));

    }

    @Test
    public void testCalculateSellLimitSize_Margin() throws Exception {

        Request request = rBuilder.build();
        Key key = Key.from(request);
        BigDecimal price = new BigDecimal("123.45");
        when(context.isMarginable(key)).thenReturn(true);

        // Net Short 1 (0.00 -> 0.00)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("0.00"));

        // Net Short 2 (0.00 -> 0.00)
        doReturn(new BigDecimal("+61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("0.00"));

        // Net Short 3 (12.30 -> 12.25)
        doReturn(new BigDecimal("+73.8")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("-49.2")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("12.25"));

        // Equal (61.50 -> 61.50)
        doReturn(new BigDecimal("123")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("0")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 1 (61.50 -> 61.50)
        doReturn(new BigDecimal("73.8")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("49.2")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 2 (61.50 -> 61.50)
        doReturn(new BigDecimal("61.5")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("61.5")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("61.50"));

        // Net Long 3 (61.50 -> 61.50)
        doReturn(new BigDecimal("0")).when(target).calculateFundingExposureSize(context, request, price);
        doReturn(new BigDecimal("123")).when(target).calculateInstrumentExposureSize(context, request);
        assertEquals(target.calculateSellLimitSize(context, request, price), new BigDecimal("61.50"));

    }

}
