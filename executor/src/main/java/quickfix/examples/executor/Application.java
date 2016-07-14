/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix.examples.executor;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.DataDictionaryProvider;
import quickfix.DoNotSend;
import quickfix.FieldConvertError;
import quickfix.FieldNotFound;
import quickfix.FixVersions;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.LogUtil;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.UnsupportedMessageType;
import quickfix.field.ApplVerID;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.Currency;
import quickfix.field.ExecID;
import quickfix.field.ExecTransType;
import quickfix.field.ExecType;
import quickfix.field.IDSource;
import quickfix.field.IOIID;
import quickfix.field.IOINaturalFlag;
import quickfix.field.IOIRefID;
import quickfix.field.IOIShares;
import quickfix.field.IOITransType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LastShares;
import quickfix.field.LeavesQty;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OnBehalfOfSubID;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Quantity;
import quickfix.field.SecurityDesc;
import quickfix.field.SecurityID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.ValidUntilTime;
import quickfix.fix41.Message.Header;
import quickfix.fix41.NewOrderSingle;
import quickfix.fix41.OrderCancelReplaceRequest;

public class Application extends quickfix.MessageCracker implements quickfix.Application {
    private static final String DEFAULT_MARKET_PRICE_KEY = "DefaultMarketPrice";
    private static final String ALWAYS_FILL_LIMIT_KEY = "AlwaysFillLimitOrders";
    private static final String VALID_ORDER_TYPES_KEY = "ValidOrderTypes";
    private static final String PRICETOLERANCE = "PriceTolerance";
    private static DecimalFormat df2 = new DecimalFormat(".##");

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final boolean alwaysFillLimitOrders;
    private final HashSet<String> validOrderTypes = new HashSet<String>();
    private MarketDataProvider marketDataProvider;
    private SessionID sessionID;
    public Map<String,NewOrderSingle> cachedNewSingleOrderMap = new HashMap<String, NewOrderSingle>();
    public Map<String,OrderCancelReplaceRequest> cachedOrderCancelReplaceRequestMap = new HashMap<String, OrderCancelReplaceRequest>();
    public Map<String,Object> nSOMainMap = new HashMap<String, Object>();

    private Random random = new Random();
    private IOIsender ioiSender;
    public SessionSettings settings;
    boolean ioiThreadStarted= false;
    int priceTol;
    

	public Application(SessionSettings settings) throws ConfigError, FieldConvertError {
        initializeValidOrderTypes(settings);
        initializeMarketDataProvider(settings);
        initializePriceTolearance(settings);
        alwaysFillLimitOrders = settings.isSetting(ALWAYS_FILL_LIMIT_KEY) && settings.getBool(ALWAYS_FILL_LIMIT_KEY);
    }

    private void initializePriceTolearance(SessionSettings settings) throws ConfigError, FieldConvertError {
        if (settings.isSetting(PRICETOLERANCE)) {
            if (priceTol == 0) {
                  priceTol = (int)settings.getLong(PRICETOLERANCE);
            } else {
            	  priceTol = 5;
            }
        }
    }

	private void initializeMarketDataProvider(SessionSettings settings) throws ConfigError, FieldConvertError {
        if (settings.isSetting(DEFAULT_MARKET_PRICE_KEY)) {
            if (marketDataProvider == null) {
                final double defaultMarketPrice = settings.getDouble(DEFAULT_MARKET_PRICE_KEY);
                marketDataProvider = new MarketDataProvider() {
                    public double getAsk(String symbol) {
                        return defaultMarketPrice;
                    }

                    public double getBid(String symbol) {
                        return defaultMarketPrice;
                    }
                };
            } else {
                log.warn("Ignoring " + DEFAULT_MARKET_PRICE_KEY + " since provider is already defined.");
            }
        }
    }

    private void initializeValidOrderTypes(SessionSettings settings) throws ConfigError, FieldConvertError {
        if (settings.isSetting(VALID_ORDER_TYPES_KEY)) {
            List<String> orderTypes = Arrays
                    .asList(settings.getString(VALID_ORDER_TYPES_KEY).trim().split("\\s*,\\s*"));
            validOrderTypes.addAll(orderTypes);
        } else {
            validOrderTypes.add(OrdType.LIMIT + "");
        }
    }

    public void onCreate(SessionID sessionID) {
        Session.lookupSession(sessionID).getLog().onEvent("Valid order types: " + validOrderTypes);
    }

    public void onLogon(SessionID sessionID) {
    }

    public void onLogout(SessionID sessionID) {
    }

    public void toAdmin(quickfix.Message message, SessionID sessionID) {
    }

    public void toApp(quickfix.Message message, SessionID sessionID) throws DoNotSend {
    }

    public void fromAdmin(quickfix.Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat,
            IncorrectTagValue, RejectLogon {
    }

    public void fromApp(quickfix.Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat,
            IncorrectTagValue, UnsupportedMessageType {
        crack(message, sessionID);
    }

    public void onMessage(quickfix.fix40.NewOrderSingle order, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        try {
            validateOrder(order);

            OrderQty orderQty = order.getOrderQty();

            Price price = getPrice(order);

            quickfix.fix40.ExecutionReport accept = new quickfix.fix40.ExecutionReport(genOrderID(), genExecID(),
                    new ExecTransType(ExecTransType.NEW), new OrdStatus(OrdStatus.NEW), order.getSymbol(), order.getSide(),
                    orderQty, new LastShares(0), new LastPx(0), new CumQty(0), new AvgPx(0));

            accept.set(order.getClOrdID());
            sendMessage(sessionID, accept);

            if (isOrderExecutable(order, price)) {
                quickfix.fix40.ExecutionReport fill = new quickfix.fix40.ExecutionReport(genOrderID(), genExecID(),
                        new ExecTransType(ExecTransType.NEW), new OrdStatus(OrdStatus.FILLED), order.getSymbol(), order
                                .getSide(), orderQty, new LastShares(orderQty.getValue()), new LastPx(price.getValue()),
                        new CumQty(orderQty.getValue()), new AvgPx(price.getValue()));

                fill.set(order.getClOrdID());

                sendMessage(sessionID, fill);
            }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }

    private boolean isOrderExecutable(Message order, Price price) throws FieldNotFound {
        if (order.getChar(OrdType.FIELD) == OrdType.LIMIT) {
            BigDecimal limitPrice = new BigDecimal(order.getString(Price.FIELD));
            char side = order.getChar(Side.FIELD);
            BigDecimal thePrice = new BigDecimal("" + price.getValue());

            return (side == Side.BUY && thePrice.compareTo(limitPrice) <= 0)
                    || ((side == Side.SELL || side == Side.SELL_SHORT) && thePrice.compareTo(limitPrice) >= 0);
        }
        return true;
    }

    private Price getPrice(Message message) throws FieldNotFound {
        Price price;
        if (message.getChar(OrdType.FIELD) == OrdType.LIMIT && alwaysFillLimitOrders) {
            price = new Price(message.getDouble(Price.FIELD));
        } else {
            if (marketDataProvider == null) {
                throw new RuntimeException("No market data provider specified for market order");
            }
            char side = message.getChar(Side.FIELD);
            if (side == Side.BUY) {
                price = new Price(marketDataProvider.getAsk(message.getString(Symbol.FIELD)));
            } else if (side == Side.SELL || side == Side.SELL_SHORT) {
                price = new Price(marketDataProvider.getBid(message.getString(Symbol.FIELD)));
            } else {
                throw new RuntimeException("Invalid order side: " + side);
            }
        }
        return price;
    }

    private void sendMessage(SessionID sessionID, Message message) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null) {
                throw new SessionNotFound(sessionID.toString());
            }

            DataDictionaryProvider dataDictionaryProvider = session.getDataDictionaryProvider();
            if (dataDictionaryProvider != null) {
                try {
                    dataDictionaryProvider.getApplicationDataDictionary(
                            getApplVerID(session, message)).validate(message, true);
                } catch (Exception e) {
                    LogUtil.logThrowable(sessionID, "Outgoing message failed validation: "
                            + e.getMessage(), e);
                    return;
                }
            }

            session.send(message);
        } catch (SessionNotFound e) {
            log.error(e.getMessage(), e);
        }
    }

    private ApplVerID getApplVerID(Session session, Message message) {
        String beginString = session.getSessionID().getBeginString();
        if (FixVersions.BEGINSTRING_FIXT11.equals(beginString)) {
            return new ApplVerID(ApplVerID.FIX50);
        } else {
            return MessageUtils.toApplVerID(beginString);
        }
    }

    public void onMessage(quickfix.fix41.NewOrderSingle order, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        try {
        validateOrder(order);

        OrderQty orderQty = order.getOrderQty();
        Price price = getPrice(order);

        quickfix.fix41.ExecutionReport accept = new quickfix.fix41.ExecutionReport(genOrderID(), genExecID(),
                new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.NEW), order
                        .getSymbol(), order.getSide(), orderQty, new LastShares(0), new LastPx(0), new LeavesQty(0),
                new CumQty(0), new AvgPx(0));

        accept.set(order.getClOrdID());
        sendMessage(sessionID, accept);
        
        if (order.getChar(OrdType.FIELD) == OrdType.MARKET) {
        	try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
        	 quickfix.fix41.ExecutionReport executionReport = new quickfix.fix41.ExecutionReport(genOrderID(),
                     genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(
                             OrdStatus.FILLED), order.getSymbol(), order.getSide(), orderQty, new LastShares(orderQty
                             .getValue()), new LastPx(price.getValue()), new LeavesQty(0), new CumQty(orderQty
                             .getValue()), new AvgPx(price.getValue()));

             executionReport.set(order.getClOrdID());

             sendMessage(sessionID, executionReport);
        	
        } else if (order.getChar(OrdType.FIELD) == OrdType.LIMIT) {
        	cachedNewSingleOrderMap.put(order.getSymbol().getValue(), order);
            String clOrdId = order.getString(ClOrdID.FIELD);
        	nSOMainMap.put(clOrdId,cachedNewSingleOrderMap);
        	if(!ioiThreadStarted){
        		ioiSender = new IOIsender(1000,"Ticker","RIC",sessionID);
        		Thread ioiSenderThread = new Thread(ioiSender);
        		ioiSenderThread.start();
        		ioiThreadStarted = true;
        	}
        }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }
    
	public void onMessage(quickfix.fix41.OrderCancelReplaceRequest order, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		
		try {
			if(null != nSOMainMap.get(order.getString(OrigClOrdID.FIELD))){
				cachedOrderCancelReplaceRequestMap.put(order.getSymbol().getValue(), order);
				nSOMainMap.remove(order.getString(OrigClOrdID.FIELD));
			}
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		}
	}
	
	public void onMessage(quickfix.fix41.OrderCancelRequest order, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			if (null != nSOMainMap.get(order.getString(OrigClOrdID.FIELD))) {
				nSOMainMap.remove(order.getString(OrigClOrdID.FIELD));
				OrderQty orderQty = order.getOrderQty();

				quickfix.fix41.ExecutionReport accept = new quickfix.fix41.ExecutionReport(genOrderID(), genExecID(),
						new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.CANCELED),
						new OrdStatus(OrdStatus.CANCELED), order.getSymbol(), order.getSide(), orderQty,
						new LastShares(orderQty.getValue()), new LastPx(0), new LeavesQty(0),
						new CumQty(0), new AvgPx(0));

				accept.set(order.getClOrdID());
				sendMessage(sessionID, accept);
			}
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		}
	}
	

    public void onMessage(quickfix.fix42.NewOrderSingle order, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        try {
        validateOrder(order);

        OrderQty orderQty = order.getOrderQty();
        Price price = getPrice(order);

        quickfix.fix42.ExecutionReport accept = new quickfix.fix42.ExecutionReport(genOrderID(), genExecID(),
                new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.NEW), order
                        .getSymbol(), order.getSide(), new LeavesQty(0), new CumQty(0), new AvgPx(0));

        accept.set(order.getClOrdID());
        sendMessage(sessionID, accept);

        if (isOrderExecutable(order, price)) {
            quickfix.fix42.ExecutionReport executionReport = new quickfix.fix42.ExecutionReport(genOrderID(),
                    genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(
                            OrdStatus.FILLED), order.getSymbol(), order.getSide(), new LeavesQty(0), new CumQty(
                            orderQty.getValue()), new AvgPx(price.getValue()));

            executionReport.set(order.getClOrdID());
            executionReport.set(orderQty);
            executionReport.set(new LastShares(orderQty.getValue()));
            executionReport.set(new LastPx(price.getValue()));

            sendMessage(sessionID, executionReport);
        }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }

    private void validateOrder(Message order) throws IncorrectTagValue, FieldNotFound {
        OrdType ordType = new OrdType(order.getChar(OrdType.FIELD));
        if (!validOrderTypes.contains(Character.toString(ordType.getValue()))) {
            log.error("Order type not in ValidOrderTypes setting");
            throw new IncorrectTagValue(ordType.getField());
        }
        if (ordType.getValue() == OrdType.MARKET && marketDataProvider == null) {
            log.error("DefaultMarketPrice setting not specified for market order");
            throw new IncorrectTagValue(ordType.getField());
        }
    }

    public void onMessage(quickfix.fix43.NewOrderSingle order, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        try {
        validateOrder(order);

        OrderQty orderQty = order.getOrderQty();
        Price price = getPrice(order);

        quickfix.fix43.ExecutionReport accept = new quickfix.fix43.ExecutionReport(
                    genOrderID(), genExecID(), new ExecType(ExecType.FILL), new OrdStatus(
                            OrdStatus.NEW), order.getSide(), new LeavesQty(order.getOrderQty()
                            .getValue()), new CumQty(0), new AvgPx(0));

        accept.set(order.getClOrdID());
        accept.set(order.getSymbol());
        sendMessage(sessionID, accept);

        if (isOrderExecutable(order, price)) {
            quickfix.fix43.ExecutionReport executionReport = new quickfix.fix43.ExecutionReport(genOrderID(),
                    genExecID(), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.FILLED), order.getSide(),
                    new LeavesQty(0), new CumQty(orderQty.getValue()), new AvgPx(price.getValue()));

            executionReport.set(order.getClOrdID());
            executionReport.set(order.getSymbol());
            executionReport.set(orderQty);
            executionReport.set(new LastQty(orderQty.getValue()));
            executionReport.set(new LastPx(price.getValue()));

            sendMessage(sessionID, executionReport);
        }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }

    public void onMessage(quickfix.fix44.NewOrderSingle order, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        try {
        validateOrder(order);

        OrderQty orderQty = order.getOrderQty();
        Price price = getPrice(order);

        quickfix.fix44.ExecutionReport accept = new quickfix.fix44.ExecutionReport(
                    genOrderID(), genExecID(), new ExecType(ExecType.FILL), new OrdStatus(
                            OrdStatus.NEW), order.getSide(), new LeavesQty(order.getOrderQty()
                            .getValue()), new CumQty(0), new AvgPx(0));

        accept.set(order.getClOrdID());
        accept.set(order.getSymbol());
        sendMessage(sessionID, accept);

        if (isOrderExecutable(order, price)) {
            quickfix.fix44.ExecutionReport executionReport = new quickfix.fix44.ExecutionReport(genOrderID(),
                    genExecID(), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.FILLED), order.getSide(),
                    new LeavesQty(0), new CumQty(orderQty.getValue()), new AvgPx(price.getValue()));

            executionReport.set(order.getClOrdID());
            executionReport.set(order.getSymbol());
            executionReport.set(orderQty);
            executionReport.set(new LastQty(orderQty.getValue()));
            executionReport.set(new LastPx(price.getValue()));

            sendMessage(sessionID, executionReport);
        }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }

    public void onMessage(quickfix.fix50.NewOrderSingle order, SessionID sessionID)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        try {
            validateOrder(order);

            OrderQty orderQty = order.getOrderQty();
            Price price = getPrice(order);

            quickfix.fix50.ExecutionReport accept = new quickfix.fix50.ExecutionReport(
                    genOrderID(), genExecID(), new ExecType(ExecType.FILL), new OrdStatus(
                            OrdStatus.NEW), order.getSide(), new LeavesQty(order.getOrderQty()
                            .getValue()), new CumQty(0));

            accept.set(order.getClOrdID());
            accept.set(order.getSymbol());
            sendMessage(sessionID, accept);

            if (isOrderExecutable(order, price)) {
                quickfix.fix50.ExecutionReport executionReport = new quickfix.fix50.ExecutionReport(
                        genOrderID(), genExecID(), new ExecType(ExecType.FILL), new OrdStatus(
                                OrdStatus.FILLED), order.getSide(), new LeavesQty(0), new CumQty(
                                orderQty.getValue()));

                executionReport.set(order.getClOrdID());
                executionReport.set(order.getSymbol());
                executionReport.set(orderQty);
                executionReport.set(new LastQty(orderQty.getValue()));
                executionReport.set(new LastPx(price.getValue()));
                executionReport.set(new AvgPx(price.getValue()));

                sendMessage(sessionID, executionReport);
            }
        } catch (RuntimeException e) {
            LogUtil.logThrowable(sessionID, e.getMessage(), e);
        }
    }

    public OrderID genOrderID() {
        return new OrderID(Integer.toString(++m_orderID));
    }

    public ExecID genExecID() {
        return new ExecID(Integer.toString(++m_execID));
    }

    /**
     * Allows a custom market data provider to be specified.
     *
     * @param marketDataProvider
     */
    public void setMarketDataProvider(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    private int m_orderID = 0;
    private int m_execID = 0;
    
    public class IOIsender implements Runnable {
    	InstrumentSet instruments;
    	private Integer delay;
    	private String symbolValue = "";
    	private String securityIDvalue = "";
    	SessionID sessionID;
    	
    	public IOIsender ( Integer delay, String symbol, String securityID , SessionID sessionID) {
            instruments = Executor.getInstruments();
            this.delay = delay;
            symbolValue = symbol;
            securityIDvalue = securityID;
            this.sessionID = sessionID;
    	}
    	

    	public void setDelay(Integer delay){
            this.delay = delay;
    	}
    	
     	public void setSymbol( String identifier ) {
            symbolValue = identifier;
     	}
     	
     	public void setSecurityID ( String identifier ) {
            securityIDvalue = identifier;
     	}
     	
        public void run() {
            while (0 != instruments.getCount()) {
                try {
					sendRandomIOI();
				} catch (FieldNotFound e1) {
					e1.printStackTrace();
				}
                try {
                    Thread.sleep( delay.longValue() );
                } catch ( InterruptedException e ) {}
            }
        }
    	
		public void sendRandomIOI() throws FieldNotFound {
			Instrument instrument = instruments.randomInstrument();
			if (null != cachedNewSingleOrderMap.get(instrument.getTicker())) {
				NewOrderSingle order = cachedNewSingleOrderMap.get(instrument.getTicker());
				OrderQty orderQty = order.getOrderQty();
				String price = instrument.getPrice();
				
				/*Random r = new Random();
				int value =  Integer.parseInt(price);
				int pricePrecision = priceTol;
				int Low = value - pricePrecision;
				int High = value + pricePrecision;
				int randomPrice = r.nextInt(High-Low) + Low;
				System.out.println(randomPrice +"<===randomPrice====================================================================");
				
				BigDecimal limitPrice = new BigDecimal(order.getString(Price.FIELD));
				char side = order.getChar(Side.FIELD);
				BigDecimal thePrice = new BigDecimal(String.valueOf(randomPrice));
				double d = Double.parseDouble(String.valueOf(randomPrice));*/
				Random r = new Random();
				int value =  Integer.parseInt(price);
				int pricePrecision = priceTol;
				int rangeMin = value - pricePrecision;
				int rangeMax = value + pricePrecision;
				double randomPrice = rangeMin + (rangeMax - rangeMin) * r.nextDouble();
				String format = df2.format(randomPrice);
				System.out.println(format +"<===randomPrice====================================================================");
				
				BigDecimal limitPrice = new BigDecimal(order.getString(Price.FIELD));
				char side = order.getChar(Side.FIELD);
				BigDecimal thePrice = new BigDecimal(String.valueOf(format));
				double d = Double.parseDouble(String.valueOf(format));
				

				if (side == Side.BUY && thePrice.compareTo(limitPrice) <= 0) {
					cachedNewSingleOrderMap.remove(instrument.getTicker());
					quickfix.fix41.ExecutionReport executionReport = new quickfix.fix41.ExecutionReport(genOrderID(),
		                     genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(
		                             OrdStatus.FILLED), order.getSymbol(), order.getSide(), orderQty, new LastShares(orderQty
		                             .getValue()), new LastPx(d), new LeavesQty(0), new CumQty(orderQty
		                             .getValue()), new AvgPx(d));

		             executionReport.set(order.getClOrdID());

					Application.this.sendMessage(sessionID, executionReport);
				} else if ((side == Side.SELL || side == Side.SELL_SHORT) && thePrice.compareTo(limitPrice) >= 0) {
					cachedNewSingleOrderMap.remove(instrument.getTicker());
					quickfix.fix41.ExecutionReport executionReport = new quickfix.fix41.ExecutionReport(genOrderID(),
		                     genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.FILL), new OrdStatus(
		                             OrdStatus.FILLED), order.getSymbol(), order.getSide(), orderQty, new LastShares(orderQty
		                             .getValue()), new LastPx(d), new LeavesQty(0), new CumQty(orderQty
		                             .getValue()), new AvgPx(d));

		             executionReport.set(order.getClOrdID());

					Application.this.sendMessage(sessionID, executionReport);
				}

			}
			
			if (null != cachedOrderCancelReplaceRequestMap.get(instrument.getTicker())) {
				OrderCancelReplaceRequest order = cachedOrderCancelReplaceRequestMap.get(instrument.getTicker());
				int int1 = order.getInt(OrderQty.FIELD);
				OrderQty orderQty = new OrderQty(int1);
				//OrderQty orderQty = order.getOrderQty();
				String price = instrument.getPrice();
				BigDecimal limitPrice = new BigDecimal(order.getString(Price.FIELD));
				char side = order.getChar(Side.FIELD);
				
				/*Random r = new Random();
				int value =  Integer.parseInt(price);
				int pricePrecision = priceTol;
				int Low = value - pricePrecision;
				int High = value + pricePrecision;
				int randomPrice = r.nextInt(High-Low) + Low;
				//System.out.println(randomPrice +"randomPrice====================================================================");
				BigDecimal thePrice = new BigDecimal(String.valueOf(randomPrice));
				double d = Double.parseDouble(String.valueOf(randomPrice));*/
				
				
				Random r = new Random();
				int value =  Integer.parseInt(price);
				int pricePrecision = priceTol;
				int rangeMin = value - pricePrecision;
				int rangeMax = value + pricePrecision;
				double randomPrice = rangeMin + (rangeMax - rangeMin) * r.nextDouble();
				String format = df2.format(randomPrice);
				System.out.println(format +"<===randomPrice====================================================================");
				
				BigDecimal thePrice = new BigDecimal(String.valueOf(format));
				double d = Double.parseDouble(String.valueOf(format));

				if (side == Side.BUY && thePrice.compareTo(limitPrice) <= 0) {
					cachedOrderCancelReplaceRequestMap.remove(instrument.getTicker());
					quickfix.fix41.ExecutionReport executionReport = new quickfix.fix41.ExecutionReport(genOrderID(),
		                     genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.REPLACE), new OrdStatus(
		                             OrdStatus.REPLACED), order.getSymbol(), order.getSide(), orderQty, new LastShares(orderQty
		                             .getValue()), new LastPx(d), new LeavesQty(0), new CumQty(orderQty
		                             .getValue()), new AvgPx(d));

		             executionReport.set(order.getClOrdID());

					Application.this.sendMessage(sessionID, executionReport);
				} else if ((side == Side.SELL || side == Side.SELL_SHORT) && thePrice.compareTo(limitPrice) >= 0) {
					cachedOrderCancelReplaceRequestMap.remove(instrument.getTicker());
					quickfix.fix41.ExecutionReport executionReport = new quickfix.fix41.ExecutionReport(genOrderID(),
		                     genExecID(), new ExecTransType(ExecTransType.NEW), new ExecType(ExecType.REPLACE), new OrdStatus(
		                             OrdStatus.REPLACED), order.getSymbol(), order.getSide(), orderQty, new LastShares(orderQty
		                             .getValue()), new LastPx(d), new LeavesQty(0), new CumQty(orderQty
		                             .getValue()), new AvgPx(d));

		             executionReport.set(order.getClOrdID());
					Application.this.sendMessage(sessionID, executionReport);
				}

			}

			IOI ioi = new IOI();
			ioi.setType("NEW");

			// Side
			ioi.setSide("BUY");
			if (random.nextBoolean())
				ioi.setSide("SELL");

			// IOIShares
			Integer quantity = new Integer(random.nextInt(1000) * 100 + 100);
			ioi.setQuantity(quantity);

			// Symbol
			String value = "";
			if (symbolValue.equals("Ticker"))
				value = instrument.getTicker();
			if (symbolValue.equals("RIC"))
				value = instrument.getRIC();
			if (symbolValue.equals("Sedol"))
				value = instrument.getSedol();
			if (symbolValue.equals("Cusip"))
				value = instrument.getCusip();
			if (value.equals(""))
				value = "<MISSING>";
			ioi.setSymbol(value);
			Symbol symbol = new Symbol(ioi.getSymbol());

			// *** Optional fields ***
			// SecurityID
			value = "";
			if (securityIDvalue.equals("Ticker"))
				value = instrument.getTicker();
			if (securityIDvalue.equals("RIC"))
				value = instrument.getRIC();
			if (securityIDvalue.equals("Sedol"))
				value = instrument.getSedol();
			if (securityIDvalue.equals("Cusip"))
				value = instrument.getCusip();
			if (value.equals(""))
				value = "<MISSING>";
			ioi.setSecurityID(value);

			// IDSource
			if (securityIDvalue.equals("Ticker"))
				ioi.setIDSource("TICKER");
			if (securityIDvalue.equals("RIC"))
				ioi.setIDSource("RIC");
			if (securityIDvalue.equals("Sedol"))
				ioi.setIDSource("SEDOL");
			if (securityIDvalue.equals("Cusip"))
				ioi.setIDSource("CUSIP");
			if (ioi.getSecurityID().equals("<MISSING>"))
				ioi.setIDSource("UNKNOWN");

			// Price
			int pricePrecision = 4;
			double factor = Math.pow(10, pricePrecision);
			double price = Math.round(random.nextDouble() * 100 * factor) / factor;
			ioi.setPrice(price);

			// IOINaturalFlag
			ioi.setNatural("No");
			if (random.nextBoolean())
				ioi.setNatural("Yes");

			sendIOI(ioi);

		}


		 public void sendIOI( IOI ioi ) {
        // *** Required fields ***
        // IOIid
        IOIID ioiID = new IOIID( ioi.getID() );

        // IOITransType
        IOITransType ioiType = null;
        if ( ioi.getType().equals("NEW") )
            ioiType = new IOITransType( IOITransType.NEW );
        if ( ioi.getType().equals("CANCEL") )
            ioiType = new IOITransType( IOITransType.CANCEL );
        if ( ioi.getType().equals("REPLACE") )
            ioiType = new IOITransType( IOITransType.REPLACE );
        
        // Side
        Side side = null;
        if ( ioi.getSide().equals("BUY") ) side = new Side( Side.BUY );
        if ( ioi.getSide().equals("SELL") ) side = new Side( Side.SELL );
        if ( ioi.getSide().equals("UNDISCLOSED") )
            side = new Side( Side.UNDISCLOSED );

        // IOIShares
        IOIShares shares = new IOIShares( ioi.getQuantity().toString() );

        // Symbol
        Symbol symbol = new Symbol( ioi.getSymbol() );

        // Construct IOI from required fields
        quickfix.fix41.IndicationofInterest fixIOI = 
            new quickfix.fix41.IndicationofInterest(
            ioiID, ioiType, symbol, side, shares);

        // *** Conditionally required fields ***
        // IOIRefID
        IOIRefID ioiRefID = null;
        if ( ioi.getType().equals("CANCEL") || ioi.getType().equals("REPLACE")){
            ioiRefID = new IOIRefID( ioi.getRefID() );
            fixIOI.set(ioiRefID);
        }
        
        // *** Optional fields ***
        // SecurityID
        SecurityID securityID = new SecurityID( ioi.getSecurityID() );
        fixIOI.set( securityID );

        // IDSource
        IDSource idSource = null;
        if (ioi.getIDSource().equals("TICKER"))
            idSource = new IDSource( IDSource.EXCHANGE_SYMBOL );
        if (ioi.getIDSource().equals("RIC"))
            idSource = new IDSource( IDSource.RIC_CODE );
        if (ioi.getIDSource().equals("SEDOL"))
            idSource = new IDSource( IDSource.SEDOL );
        if (ioi.getIDSource().equals("CUSIP"))
            idSource = new IDSource( IDSource.CUSIP );
        if (ioi.getIDSource().equals("UNKOWN"))
            idSource = new IDSource( "100" );
        fixIOI.set( idSource );

        // Price
        Price price = new Price( ioi.getPrice() );
        fixIOI.set( price );

        // IOINaturalFlag
        IOINaturalFlag ioiNaturalFlag = new IOINaturalFlag();
        if ( ioi.getNatural().equals("YES") ) 
            ioiNaturalFlag.setValue( true );
        if ( ioi.getNatural().equals("NO") ) 
            ioiNaturalFlag.setValue( false );
        fixIOI.set( ioiNaturalFlag );

        // SecurityDesc
        Instrument instrument = 
        		Executor.getInstruments().getInstrument(ioi.getSymbol());
        String name = "Unknown security";
        if ( instrument != null ) name = instrument.getName();
        SecurityDesc desc = new SecurityDesc( name );
        fixIOI.set( desc );

        // ValidUntilTime
        int minutes = 30;
        long expiry = new Date().getTime() + 1000 * 60 * minutes;
        Date validUntil = new Date( expiry );
        ValidUntilTime validTime = new ValidUntilTime( validUntil );
        fixIOI.set( validTime );

        //Currency
        Currency currency = new Currency( "USD" );
        fixIOI.set( currency );

        // *** Send message ***
        sendMessage(fixIOI);
    }


		public void sendMessage( Message message ) {
        String oboCompID = "<UNKNOWN>";
        String oboSubID = "<UNKNOWN>";
        boolean sendoboCompID = false;
        boolean sendoboSubID = false;
        
        try {
            oboCompID = settings.getString(sessionID, "OnBehalfOfCompID");
            oboSubID = settings.getString(sessionID, "OnBehalfOfSubID");
            sendoboCompID = settings.getBool("FIXimulatorSendOnBehalfOfCompID");
            sendoboSubID = settings.getBool("FIXimulatorSendOnBehalfOfSubID");
        } catch ( Exception e ) {}
        
        // Add OnBehalfOfCompID
        if ( sendoboCompID && !oboCompID.equals("") ) {
            OnBehalfOfCompID onBehalfOfCompID = new OnBehalfOfCompID(oboCompID);
            Header header = (Header) message.getHeader();
            header.set( onBehalfOfCompID );			
        }

        // Add OnBehalfOfSubID
        if ( sendoboSubID && !oboSubID.equals("") ) {
            OnBehalfOfSubID onBehalfOfSubID = new OnBehalfOfSubID(oboSubID);
            Header header = (Header) message.getHeader();
            header.set( onBehalfOfSubID );			
        }
        
        // Send actual message
        try {
            Session.sendToTarget( message, sessionID );
        } catch ( SessionNotFound e ) { e.printStackTrace(); }
    }
    }
}
