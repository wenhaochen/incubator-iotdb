package cn.edu.thu.tsfiledb.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import cn.edu.thu.tsfiledb.service.rpc.thrift.TSIService;
import cn.edu.thu.tsfiledb.metadata.ColumnSchema;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSCloseSessionReq;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSFetchMetadataReq;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSFetchMetadataResp;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSOpenSessionReq;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSOpenSessionResp;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TSProtocolVersion;
import cn.edu.thu.tsfiledb.service.rpc.thrift.TS_SessionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TsfileConnection implements Connection {
    private static final Logger LOGGER = LoggerFactory.getLogger(TsfileConnection.class);
    private TsfileConnectionParams params;
    private boolean isClosed = true;
    private SQLWarning warningChain = null;
    private TTransport transport;
    private TSIService.Iface client = null;
    private TS_SessionHandle sessionHandle = null;
    private final List<TSProtocolVersion> supportedProtocols = new LinkedList<TSProtocolVersion>();
    // private int loginTimeout = 0;
    private TSProtocolVersion protocol;

    public TsfileConnection(String url, Properties info) throws SQLException, TTransportException {
	if (url == null) {
	    throw new TsfileURLException("Input url cannot be null");
	}
	params = Utils.parseURL(url, info);

	supportedProtocols.add(TSProtocolVersion.TSFILE_SERVICE_PROTOCOL_V1);

	openTransport();

	client = new TSIService.Client(new TBinaryProtocol(transport));
	// open client session
	openSession();

	// Wrap the client with a thread-safe proxy to serialize the RPC calls
	client = newSynchronizedClient(client);
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public <T> T unwrap(Class<T> arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void abort(Executor arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void clearWarnings() throws SQLException {
	warningChain = null;
    }

    @Override
    public void close() throws SQLException {
	if (isClosed)
	    return;
	TSCloseSessionReq req = new TSCloseSessionReq(sessionHandle);
	try {
	    client.CloseSession(req);
	} catch (TException e) {
	    throw new SQLException("Error occurs when closing session at server", e);
	} finally {
	    isClosed = true;
	    if (transport != null)
		transport.close();
	}
    }

    @Override
    public void commit() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Array createArrayOf(String arg0, Object[] arg1) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Blob createBlob() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Clob createClob() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public NClob createNClob() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Statement createStatement() throws SQLException {
	if (isClosed) {
	    throw new SQLException("Cannot create statement because connection is closed");
	}
	return new TsfileStatement(this, client, sessionHandle);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
	if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
	    throw new SQLException(
		    String.format("Statement with resultset concurrency %d is not supported", resultSetConcurrency));
	}
	if (resultSetType == ResultSet.TYPE_SCROLL_SENSITIVE) {
	    throw new SQLException(String.format("Statement with resultset type %d is not supported", resultSetType));
	}
	return new TsfileStatement(this, client, sessionHandle);
    }

    @Override
    public Statement createStatement(int arg0, int arg1, int arg2) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Struct createStruct(String arg0, Object[] arg1) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
	return false;
    }

    @Override
    public String getCatalog() throws SQLException {
	return "no cata log";
    }

    @Override
    public Properties getClientInfo() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public String getClientInfo(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public int getHoldability() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
	if (isClosed) {
	    throw new SQLException("Cannot create statement because connection is closed");
	}

	try {
	    return getMetaDataFromServer();
	} catch (TException e) {
	    boolean flag = reconnect();
	    if (flag) {
		try {
		    return getMetaDataFromServer();
		} catch (TException e2) {
		    throw new SQLException("Fail to fetch metadata after reconnecting. please check server status");
		}
	    } else {
		throw new SQLException("Fail to reconnect to server when fetching metadata. please check server status");
	    }
	}
    }

    private DatabaseMetaData getMetaDataFromServer() throws TException, TsfileSQLException  {
	TSFetchMetadataResp resp = client.FetchMetadata(new TSFetchMetadataReq());
	Utils.verifySuccess(resp.getStatus());
	Map<String, List<ColumnSchema>> seriesMap = Utils.convertAllSchema(resp.getSeriesMap());
	Map<String, List<String>> deltaObjectMap = resp.getDeltaObjectMap();
	String metadataInJson = resp.getMetadataInJson();
	return new TsfileDatabaseMetadata(this, seriesMap, deltaObjectMap, metadataInJson);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public String getSchema() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
	return Connection.TRANSACTION_NONE;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
	return warningChain;
    }

    @Override
    public boolean isClosed() throws SQLException {
	return isClosed;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public boolean isValid(int arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public String nativeSQL(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public CallableStatement prepareCall(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public CallableStatement prepareCall(String arg0, int arg1, int arg2) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public CallableStatement prepareCall(String arg0, int arg1, int arg2, int arg3) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
	    throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
	    int resultSetHoldability) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void releaseSavepoint(Savepoint arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void rollback() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void rollback(Savepoint arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setAutoCommit(boolean arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setCatalog(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setClientInfo(Properties arg0) throws SQLClientInfoException {
	throw new SQLClientInfoException("Method not supported", null);
    }

    @Override
    public void setClientInfo(String arg0, String arg1) throws SQLClientInfoException {
	throw new SQLClientInfoException("Method not supported", null);
    }

    @Override
    public void setHoldability(int arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setNetworkTimeout(Executor arg0, int arg1) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setReadOnly(boolean arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public Savepoint setSavepoint(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setSchema(String arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setTransactionIsolation(int arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> arg0) throws SQLException {
	throw new SQLException("Method not supported");
    }

    private void openTransport() throws TTransportException {
	transport = new TSocket(params.getHost(), params.getPort());
	if (!transport.isOpen()) {
	    transport.open();
	    LOGGER.debug("Connect to host {} port {}", params.getHost(), params.getPort());
	}
    }

    private void openSession() throws SQLException {
	TSOpenSessionReq openReq = new TSOpenSessionReq(TSProtocolVersion.TSFILE_SERVICE_PROTOCOL_V1);

	openReq.setUsername(params.getUsername());
	openReq.setPassword(params.getPassword());

	try {
	    TSOpenSessionResp openResp = client.OpenSession(openReq);

	    // validate connection
	    Utils.verifySuccess(openResp.getStatus());
	    if (!supportedProtocols.contains(openResp.getServerProtocolVersion())) {
		throw new TException("Unsupported tsfile protocol");
	    }
	    setProtocol(openResp.getServerProtocolVersion());
	    sessionHandle = openResp.getSessionHandle();
	} catch (TException e) {
	    throw new SQLException(
		    String.format("Can not establish connection with %s. because %s", params.getJdbcUriString()),
		    e.getMessage());
	}
	isClosed = false;
    }

    public boolean reconnect() {
	boolean flag = false;
	for (int i = 1; i <= TsfileConfig.RETRY_NUM; i++) {
	    LOGGER.debug("Try to connect to server for %d times", i);
	    try {
		if (transport != null) {
		    openTransport();
		    client = new TSIService.Client(new TBinaryProtocol(transport));
		    openSession();
		    client = newSynchronizedClient(client);
		    flag = true;
		    break;
		}
	    } catch (Exception e) {
		try {
		    Thread.sleep(TsfileConfig.RETRY_INTERVAL);
		} catch (InterruptedException e1) {
		}
	    }
	}
	return flag;
    }

    public static TSIService.Iface newSynchronizedClient(TSIService.Iface client) {
	return (TSIService.Iface) Proxy.newProxyInstance(TsfileConnection.class.getClassLoader(),
		new Class[] { TSIService.Iface.class }, new SynchronizedHandler(client));
    }

    public TSProtocolVersion getProtocol() {
	return protocol;
    }

    public void setProtocol(TSProtocolVersion protocol) {
	this.protocol = protocol;
    }

    private static class SynchronizedHandler implements InvocationHandler {
	private final TSIService.Iface client;

	SynchronizedHandler(TSIService.Iface client) {
	    this.client = client;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
	    try {
		synchronized (client) {
		    return method.invoke(client, args);
		}
	    } catch (InvocationTargetException e) {
		// all IFace APIs throw TException
		if (e.getTargetException() instanceof TException) {
		    throw (TException) e.getTargetException();
		} else {
		    // should not happen
		    throw new TException("Error in calling method " + method.getName(), e.getTargetException());
		}
	    } catch (Exception e) {
		throw new TException("Error in calling method " + method.getName(), e);
	    }
	}
    }
}