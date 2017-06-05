package cn.edu.thu.tsfiledb.engine.overflow.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;

import cn.edu.thu.tsfile.common.conf.TSFileConfig;
import cn.edu.thu.tsfile.common.conf.TSFileDescriptor;
import cn.edu.thu.tsfile.file.metadata.enums.TSDataType;
import cn.edu.thu.tsfile.timeseries.filter.definition.SingleSeriesFilterExpression;
import cn.edu.thu.tsfile.timeseries.read.query.DynamicOneColumnData;
import cn.edu.thu.tsfiledb.conf.TSFileDBConfig;
import cn.edu.thu.tsfiledb.conf.TSFileDBDescriptor;
import cn.edu.thu.tsfiledb.engine.bufferwrite.Action;
import cn.edu.thu.tsfiledb.engine.bufferwrite.FileNodeConstants;
import cn.edu.thu.tsfiledb.engine.exception.OverflowProcessorException;
import cn.edu.thu.tsfiledb.engine.filenode.FilterUtilsForOverflow;

public class BigDataForOverflowTest {

	private String nameSpacePath = "root.vehicle.d0";
	private String overflowfilePath = null;
	private String overflowrestorefilePath = null;
	private String overflowmergefilePath = null;
	private Map<String, Object> parameters = null;
	private OverflowProcessor ofprocessor = null;
	private TSFileDBConfig tsdbconfig = TSFileDBDescriptor.getInstance().getConfig();
	private TSFileConfig tsconfig = TSFileDescriptor.getInstance().getConfig();
	private String deltaObjectId = "root.vehicle.d0";
	private String[] measurementIds = { "s0", "s1", "s2", "s3", "s4", "s5" };
	private String measurementId = "s0";
	private TSDataType[] dataTypes = { TSDataType.INT64, TSDataType.INT32, TSDataType.FLOAT, TSDataType.DOUBLE,
			TSDataType.BOOLEAN, TSDataType.BYTE_ARRAY };

	private Action overflowflushaction = new Action() {

		@Override
		public void act() throws Exception {
			System.out.println("overflow flush action");
		}
	};

	private Action filenodeflushaction = new Action() {

		@Override
		public void act() throws Exception {
			System.out.println("filenode flush action");
		}
	};

	private Action filenodemanagerbackupaction = new Action() {

		@Override
		public void act() throws Exception {
			System.out.println("filenode manager backup action");
		}
	};

	private Action filenodemanagerflushaction = new Action() {

		@Override
		public void act() throws Exception {
			System.out.println("filenode manager flush action");
		}
	};

	@Before
	public void setUp() throws Exception {

		EngineTestHelper.delete(nameSpacePath);
		parameters = new HashMap<String, Object>();
		parameters.put(FileNodeConstants.OVERFLOW_FLUSH_ACTION, overflowflushaction);
		parameters.put(FileNodeConstants.FILENODE_PROCESSOR_FLUSH_ACTION, filenodeflushaction);
		parameters.put(FileNodeConstants.OVERFLOW_BACKUP_MANAGER_ACTION, filenodemanagerbackupaction);
		parameters.put(FileNodeConstants.OVERFLOW_FLUSH_MANAGER_ACTION, filenodemanagerflushaction);

		tsdbconfig.overflowDataDir = "";
		tsconfig.rowGroupSize = 1024 * 1024 * 10;
		overflowfilePath = tsdbconfig.overflowDataDir + nameSpacePath + File.separatorChar + nameSpacePath
				+ ".overflow";
		overflowrestorefilePath = overflowfilePath + ".restore";
		overflowmergefilePath = overflowfilePath + ".merge";

	}

	@After
	public void tearDown() throws Exception {
		EngineTestHelper.delete(nameSpacePath);
	}

//	@Test
	public void testBigData() {

		long step = 10000;
		long pass = step * 10;
		long length = step * 1000;
		try {
			ofprocessor = new OverflowProcessor(nameSpacePath, parameters);
		} catch (OverflowProcessorException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		for (long i = 1; i <= length; i++) {
			if (i > 0 && i % pass == 0) {
				System.out.println(i / pass + " pass");
			}
			try {
				ofprocessor.insert(deltaObjectId, measurementId, i, TSDataType.INT64, String.valueOf(i));
			} catch (OverflowProcessorException e) {
				e.printStackTrace();
				fail(e.getMessage());
			}
		}
		// construct the time filter for the query
		SingleSeriesFilterExpression timeFilter = FilterUtilsForOverflow.construct(null, null, "0",
				"(>=" + 0 + ")&" + "(<=" + length + ")");
		List<Object> queryResult = ofprocessor.query(deltaObjectId, measurementId, timeFilter, null, null);
		DynamicOneColumnData insertData = (DynamicOneColumnData) queryResult.get(0);
		assertEquals(length, insertData.length);
		try {
			ofprocessor.close();
		} catch (OverflowProcessorException e1) {
			e1.printStackTrace();
			fail(e1.getMessage());
		}

		try {
			assertEquals(true, new File(overflowrestorefilePath).exists());
			ofprocessor.switchWorkingToMerge();
			assertEquals(true, new File(overflowmergefilePath).exists());
			assertEquals(true, new File(overflowfilePath).exists());
		} catch (OverflowProcessorException e1) {
			e1.printStackTrace();
			fail(e1.getMessage());
		}
		try {
			ofprocessor.insert(deltaObjectId, measurementId, length+1,TSDataType.INT64,String.valueOf(length)+1);
		} catch (OverflowProcessorException e1) {
			e1.printStackTrace();
			fail(e1.getMessage());
		}
		
		queryResult = ofprocessor.query(deltaObjectId, measurementId, timeFilter, null, null);
		insertData = (DynamicOneColumnData) queryResult.get(0);
		assertEquals(length, insertData.length);
		try {
			ofprocessor.switchMergeToWorking();
			ofprocessor.close();
		} catch (OverflowProcessorException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

}