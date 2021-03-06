package cn.edu.thu.tsfile.timeseries.read;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import cn.edu.thu.tsfile.common.utils.ReadWriteStreamUtils;
import cn.edu.thu.tsfile.common.utils.BytesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.edu.thu.tsfile.common.utils.Binary;
import cn.edu.thu.tsfile.common.utils.TSRandomAccessFileReader;
import cn.edu.thu.tsfile.encoding.decoder.Decoder;
import cn.edu.thu.tsfile.encoding.decoder.DeltaBinaryDecoder;
import cn.edu.thu.tsfile.file.metadata.TSDigest;
import cn.edu.thu.tsfile.file.metadata.enums.CompressionTypeName;
import cn.edu.thu.tsfile.file.metadata.enums.TSDataType;
import cn.edu.thu.tsfile.timeseries.filter.definition.SingleSeriesFilterExpression;
import cn.edu.thu.tsfile.timeseries.filter.utils.DigestForFilter;
import cn.edu.thu.tsfile.timeseries.filter.visitorImpl.DigestVisitor;
import cn.edu.thu.tsfile.timeseries.filter.visitorImpl.SingleValueVisitor;
import cn.edu.thu.tsfile.timeseries.filter.visitorImpl.SingleValueVisitorFactory;
import cn.edu.thu.tsfile.format.Digest;
import cn.edu.thu.tsfile.format.PageHeader;
import cn.edu.thu.tsfile.timeseries.read.query.DynamicOneColumnData;

/**
 * @description This class is mainly used to read one column of data in
 *              RowGroup. It provides a number of different methods to read data
 *              in different ways.
 * @author Jinrui Zhang
 *
 */
public class ValueReader {

	protected static final Logger log = LoggerFactory.getLogger(ValueReader.class);

	protected Decoder decoder;
	protected Decoder timeDecoder;
	protected Decoder freqDecoder;
	protected long fileOffset = -1;
	protected long totalSize = -1;
	protected TSDataType dataType;
	protected TSDigest digest;
	protected TSRandomAccessFileReader raf;
	protected List<String> enumValues;
	protected CompressionTypeName compressionTypeName;
	protected long rowNums;
	
	// save the mainFrequency of this page
	protected List<float[]> mainFrequency = null;

	/**
	 * 
	 * @param offset
	 *            Offset for current column in file.
	 * @param totalSize
	 *            Total bytes size for this column.
	 * @param dataType
	 *            Data type of this column
	 * @param digest
	 *            Digest for this column.
	 */
	protected ValueReader(long offset, long totalSize, TSDataType dataType, TSDigest digest) {
		this.timeDecoder = new DeltaBinaryDecoder.LongDeltaDecoder();
		this.fileOffset = offset;
		this.totalSize = totalSize;

		this.decoder = null;
		this.dataType = dataType;
		this.digest = digest;
	}

	public ValueReader(long offset, long totalSize, TSDataType dataType, TSDigest digest, TSRandomAccessFileReader raf,
			CompressionTypeName compressionTypeName) {
		this(offset, totalSize, dataType, digest);
		this.compressionTypeName = compressionTypeName;
		this.raf = raf;
	}
	
	/**
	 * 
	 * @param offset Column Offset in current file
	 * @param totalSize Total bytes size for this column
	 * @param dataType DataType for this column
	 * @param digest Digest for this column including time and value digests
	 * @param raf RandomAccessFileReader stream
	 * @param enumValues EnumValues if this column's dataType is ENUM
	 * @param compressionTypeName CompressionType used for this column
	 * @param rowNums Total of rows for this column 
	 */
	public ValueReader(long offset, long totalSize, TSDataType dataType, TSDigest digest, TSRandomAccessFileReader raf,
			List<String> enumValues, CompressionTypeName compressionTypeName, long rowNums) {
		this(offset, totalSize, dataType, digest, raf, compressionTypeName);
		this.enumValues = enumValues;
		this.rowNums = rowNums;
	}

	/**
	 * Read time value from the page and return them.
	 * 
	 * @param page
	 * @param size
	 * @param skip
	 *            If skip is true, then return long[] which is null.
	 * @throws IOException
	 */
	protected long[] initTimeValue(InputStream page, int size, boolean skip) throws IOException {
		long[] res = null;
		int idx = 0;

		int length = ReadWriteStreamUtils.readUnsignedVarInt(page);
		byte[] buf = new byte[length];
		int readSize = 0;
		readSize = page.read(buf, 0, length);
		if (readSize != length) {
			throw new IOException("Expect byte size : " + totalSize + ". Read size : " + readSize);
		}
		if (!skip) {
			ByteArrayInputStream bis = new ByteArrayInputStream(buf);
			res = new long[size];
			while (timeDecoder.hasNext(bis)) {
				res[idx++] = timeDecoder.readLong(bis);
			}
		}

		return res;
	}

	private ByteArrayInputStream initBAIS() throws IOException {
		int length = (int) this.totalSize;
		byte[] buf = new byte[length];
		int readSize = 0;

		raf.seek(fileOffset);
		readSize = raf.read(buf, 0, length);
		if (readSize != length) {
			throw new IOException("Expect byte size : " + length + ". Read size : " + readSize);
		}

		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		return bais;
	}
	
	private ByteArrayInputStream initBAISForOnePage(long pageOffset) throws IOException {
		int length = (int) (this.totalSize - (pageOffset - fileOffset));
		byte[] buf = new byte[length]; 
		int readSize = 0;
		raf.seek(pageOffset);
		readSize = raf.read(buf, 0, length);
		if (readSize != length) {
			throw new IOException("Expect byte size : " + length + ". Read size : " + readSize);
		}

		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		return bais;
	}

	/**
	 * Judge whether current column is satisfied for given filters
	 */
	private boolean columnSatisfied(SingleSeriesFilterExpression valueFilter, SingleSeriesFilterExpression timeFilter,
			SingleSeriesFilterExpression freqFilter) {
		if (valueFilter == null) {
			return true;
		}
		TSDigest digest = getDigest();
		DigestForFilter digestFF = null;

		if (getDataType() == TSDataType.ENUMS) {
			byte[] minValue = new byte[digest.min.remaining()];
			digest.min.get(minValue);
			String minString = enumValues.get(BytesUtils.bytesToInt(minValue) - 1);
			byte[] maxValue = new byte[digest.max.remaining()];
			digest.max.get(maxValue);
			String maxString = enumValues.get(BytesUtils.bytesToInt(maxValue) - 1);
			digestFF = new DigestForFilter(ByteBuffer.wrap(BytesUtils.StringToBytes(minString)), ByteBuffer.wrap(BytesUtils.StringToBytes(maxString)), TSDataType.BYTE_ARRAY);
		} else {
			digestFF = new DigestForFilter(digest.min, digest.max, getDataType());
		}
		log.debug("Column Digest min and max is: " + digestFF.getMinValue() + " --- " + digestFF.getMaxValue());
		DigestVisitor digestVisitor = new DigestVisitor();
		if (digestVisitor.satisfy(digestFF, valueFilter)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Judge whether current page is satisfied for given filters according to
	 * the digests of this page
	 */
	private boolean pageSatisfied(DigestForFilter timeDigestFF, DigestForFilter valueDigestFF,
			SingleSeriesFilterExpression timeFilter, SingleSeriesFilterExpression valueFilter, SingleSeriesFilterExpression freqFilter) {
		DigestVisitor digestVisitor = new DigestVisitor();
		if ((valueFilter == null && timeFilter == null)
				|| (valueFilter != null && (valueDigestFF == null || digestVisitor.satisfy(valueDigestFF, valueFilter)))
				|| (timeFilter != null && digestVisitor.satisfy(timeDigestFF, timeFilter))) {
			return true;
		}
		return false;
	}
	
	/**
	 * Read the whole column without filters.
	 * 
	 * @throws IOException
	 */
	public DynamicOneColumnData readOneColumn(DynamicOneColumnData res, int fetchSize) throws IOException {
		return readOneColumnUseFilter(res, fetchSize, null, null, null);
	}

	protected SingleValueVisitor<?> getSingleValueVisitorByDataType(TSDataType type, SingleSeriesFilterExpression filter) {
		switch (type) {
		case INT32:
			return new SingleValueVisitor<Integer>(filter);
		case INT64:
			return new SingleValueVisitor<Long>(filter);
		case FLOAT:
			return new SingleValueVisitor<Float>(filter);
		case DOUBLE:
			return new SingleValueVisitor<Double>(filter);
		default:
			return SingleValueVisitorFactory.getSingleValueVisitor(type);
		}
	}

	/**
	 * Read one column values with specific filters.
	 * 
	 * @param timeFilter
	 *            Filter for time.
	 * @param freqFilter
	 *            Filter for frequency.
	 * @param valueFilter
	 *            Filter for value.
	 * @return
	 * @throws IOException
	 */
	public DynamicOneColumnData readOneColumnUseFilter(DynamicOneColumnData res, int fetchSize,
			SingleSeriesFilterExpression timeFilter, SingleSeriesFilterExpression freqFilter, SingleSeriesFilterExpression valueFilter)
					throws IOException {

		SingleValueVisitor<?> timeVisitor = null;
		if(timeFilter != null){
			timeVisitor = getSingleValueVisitorByDataType(TSDataType.INT64, timeFilter);
		}
		SingleValueVisitor<?> valueVisitor = null;
		if(valueFilter != null){
			valueVisitor = getSingleValueVisitorByDataType(getDataType(), valueFilter);
		}
		
		if (res == null) {
			res = new DynamicOneColumnData(getDataType(), true);
			res.pageOffset = this.fileOffset;
			res.leftSize = this.totalSize;
		}
		// That res.pageOffset is -1 represents reading from the start of
		// current column.
		if (res.pageOffset == -1) {
			res.pageOffset = this.fileOffset;
		}

		// record the length of res before reading
		int currentLength = res.length;

		if (columnSatisfied(valueFilter, timeFilter, freqFilter)) {
			log.debug("ValueFilter satisfied Or ValueFilter is null. [ValueFilter] is: " + valueFilter);

			// Initialize the bis according to the offset in last read.
			ByteArrayInputStream bis = initBAISForOnePage(res.pageOffset);
			PageReader pageReader = new PageReader(bis, compressionTypeName);
			int pageCount = 0;
			while ((res.pageOffset - fileOffset) < totalSize && (res.length - currentLength) < fetchSize) {
				int lastAvailable = bis.available();

				pageCount++;
				log.debug("read page " + pageCount);
				PageHeader pageHeader = pageReader.getNextPageHeader();

				// construct valueFilter
				Digest pageDigest = pageHeader.data_page_header.getDigest();
				DigestForFilter valueDigestFF = null;
				if(pageDigest != null){
					if (getDataType() == TSDataType.ENUMS) {
						byte[] minValue = new byte[pageDigest.min.remaining()];
						pageDigest.min.get(minValue);
						String minString = enumValues.get(BytesUtils.bytesToInt(minValue) - 1);
						byte[] maxValue = new byte[pageDigest.max.remaining()];
						pageDigest.max.get(maxValue);
						String maxString = enumValues.get(BytesUtils.bytesToInt(maxValue) - 1);
						valueDigestFF = new DigestForFilter(ByteBuffer.wrap(BytesUtils.StringToBytes(minString)), ByteBuffer.wrap(BytesUtils.StringToBytes(maxString)), TSDataType.BYTE_ARRAY);
					} else {
						valueDigestFF = new DigestForFilter(pageDigest.min, pageDigest.max, getDataType());
					}
				}

				// construct timeFilter
				long mint = pageHeader.data_page_header.min_timestamp;
				long maxt = pageHeader.data_page_header.max_timestamp;
				DigestForFilter timeDigestFF = new DigestForFilter(mint, maxt);

				if (pageSatisfied(timeDigestFF, valueDigestFF, timeFilter, valueFilter, freqFilter)) {

					log.debug("page " + pageCount + " satisfied filter");

					InputStream page = pageReader.getNextPage();

					setDecoder(Decoder.getDecoderByType(pageHeader.getData_page_header().getEncoding(), getDataType()));

					// get timevalues in this page
					long[] timeValues = initTimeValue(page, pageHeader.data_page_header.num_rows, false);

					try {
						int timeIdx = 0;
						switch (dataType) {
							case BOOLEAN:
								while (decoder.hasNext(page)) {
									boolean v = decoder.readBoolean(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putBoolean(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case INT32:
								while (decoder.hasNext(page)) {
									int v = decoder.readInt(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putInt(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case INT64:
								while (decoder.hasNext(page)) {
									long v = decoder.readLong(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putLong(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case FLOAT:
								while (decoder.hasNext(page)) {
									float v = decoder.readFloat(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putFloat(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case DOUBLE:
								while (decoder.hasNext(page)) {
									double v = decoder.readDouble(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putDouble(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case BYTE_ARRAY:
								while (decoder.hasNext(page)) {
									Binary v = decoder.readBinary(page);
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(v, valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(v, valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putBinary(v);
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
							case ENUMS:
								while (decoder.hasNext(page)) {
									int v = decoder.readInt(page) - 1;
									if ((valueFilter == null && timeFilter == null)
											|| (valueFilter != null && timeFilter == null
											&& valueVisitor.satisfyObject(enumValues.get(v), valueFilter))
											|| (valueFilter == null && timeFilter != null
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))
											|| (valueFilter != null && timeFilter != null
											&& valueVisitor.satisfyObject(enumValues.get(v), valueFilter)
											&& timeVisitor.satisfyObject(timeValues[timeIdx], timeFilter))) {
										res.putBinary(Binary.valueOf(enumValues.get(v)));
										res.putTime(timeValues[timeIdx]);
									}
									timeIdx++;
								}
								break;
						default:
							throw new IOException("Data type not supported. " + dataType);
						}
					} catch (IOException e) {
						e.printStackTrace();
					}

				} else {
					pageReader.skipCurrentPage();
				}
				res.pageOffset += (lastAvailable - bis.available());
			}

			// Represents current Column has been read all. Prepare for next
			// column in another RowGroup
			if ((res.pageOffset - fileOffset) >= totalSize) {
				res.plusRowGroupIndexAndInitPageOffset();
			}
			return res;
		}
		return res;
	}

	/**
	 * Read time-value pairs whose time is be included in timeRet. WARNING: this
	 * function is only for "time" Series
	 * 
	 * @param timeRet
	 *            Array of the time.
	 * @return
	 * @throws IOException
	 */
	public DynamicOneColumnData getValuesForGivenValues(long[] timeRet) throws IOException {
		DynamicOneColumnData res = new DynamicOneColumnData(dataType, true);

		if (timeRet.length == 0) {
			return res;
		}

		int timeIdx = 0;

		ByteArrayInputStream bis = initBAIS();
		PageReader pageReader = new PageReader(bis, compressionTypeName);
		int pageCount = 0;

		while (timeIdx < timeRet.length && pageReader.hasNextPage()) {
			pageCount++;
			log.debug("read page " + pageCount);
			PageHeader pageHeader = pageReader.getNextPageHeader();

			long timeMaxv = pageHeader.data_page_header.getMax_timestamp();

			// If there may be some values acceptable in this page
			if (timeIdx < timeRet.length && timeMaxv >= timeRet[timeIdx]) {

				InputStream page = pageReader.getNextPage();

				setDecoder(Decoder.getDecoderByType(pageHeader.getData_page_header().getEncoding(), getDataType()));

				long[] timeValues = initTimeValue(page, pageHeader.data_page_header.num_rows, false);

				int i = 0;
				switch (dataType) {
					case BOOLEAN:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readBoolean(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putBoolean(decoder.readBoolean(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case INT32:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readInt(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putInt(decoder.readInt(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case INT64:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readLong(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putLong(decoder.readLong(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case FLOAT:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readFloat(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putFloat(decoder.readFloat(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case DOUBLE:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readDouble(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putDouble(decoder.readDouble(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case BYTE_ARRAY:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readBinary(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putBinary(decoder.readBinary(page));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
					case ENUMS:
						while (i < timeValues.length && timeIdx < timeRet.length) {
							while (i < timeValues.length && timeValues[i] < timeRet[timeIdx]) {
								i++;
								decoder.readInt(page);
							}
							if (i < timeValues.length && timeValues[i] == timeRet[timeIdx]) {
								res.putBinary(Binary.valueOf(enumValues.get(decoder.readInt(page) - 1)));
								res.putTime(timeValues[i]);
								i++;
								timeIdx++;
							}
							while (timeIdx < timeRet.length && i < timeValues.length && timeRet[timeIdx] < timeValues[i]) {
								timeIdx++;
							}
						}
						break;
				default:
					throw new IOException("Data Type not support");
				}
			} else {
				pageReader.skipCurrentPage();
			}
		}
		return res;
	}


	public void setDecoder(Decoder d) {
		this.decoder = d;
	}

	public void setFileOffset(long offset) {
		this.fileOffset = offset;
	}

	public long getFileOffset() {
		return this.fileOffset;
	}

	public long getTotalSize() {
		return this.totalSize;
	}

	public TSDigest getDigest() {
		return this.digest;
	}

	public TSDataType getDataType() {
		return this.dataType;
	}

	public List<float[]> getMainFrequency() {
		return mainFrequency;
	}

	public void setMainFrequency(List<float[]> mainFrequency) {
		this.mainFrequency = mainFrequency;
	}

	public long getNumRows() {
		return rowNums;
	}

	public void setNumRows(long rowNums) {
		this.rowNums = rowNums;
	}

	public List<String> getEnumValues() {
		return enumValues;
	}

	public void setEnumValues(List<String> enumValues) {
		this.enumValues = enumValues;
	}
}
