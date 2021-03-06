package cn.edu.thu.tsfile.encoding.bitPacking;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;
import cn.edu.thu.tsfile.common.utils.ReadWriteStreamUtils;

/**
 * 
 * @author Zhang Jinrui
 *
 */
public class LongPackerTest {

	@Test
	public void test() {
		Random rand = new Random();
		int byteCount = 63;

		LongPacker packer = new LongPacker(byteCount);
		ArrayList<Long> preValues = new ArrayList<>();
		int count = 1;
		byte[] bb = new byte[count * byteCount];
		int idx = 0;
		for (int i = 0; i < count; i++) {
			long[] vs = new long[8];
			for(int j = 0 ; j < 8; j ++){
				long v = rand.nextLong();
				vs[j] = v < 0 ? -v : v;
				preValues.add(vs[j]);
			}
			
			byte[] tb = new byte[byteCount];
			packer.pack8Values(vs, 0, tb);
			for (int j = 0; j < tb.length; j++) {
				bb[idx++] = tb[j];
			}
		}
		long tres[] = new long[count * 8];
		packer.unpackAllValues(bb, 0, bb.length, tres);
		
		for(int i = 0 ; i < count * 8 ; i++){
			long v = preValues.get(i);
			assertEquals(tres[i], v);
		}
	}

	@Test
	public void testPackAll() throws IOException {
		List<Long> bpList = new ArrayList<Long>();
		int bpCount = 15;
		long bpStart = 11;
		for (int i = 0; i < bpCount; i++) {
			bpList.add(bpStart);
			bpStart *= 3;
		}
		bpList.add(0L);
		int bpBitWidth = ReadWriteStreamUtils.getLongMaxBitWidth(bpList);

		LongPacker packer = new LongPacker(bpBitWidth);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		long[] value1 = new long[8];
		long[] value2 = new long[8];
		for (int i = 0; i < 8; i++) {
			value1[i] = bpList.get(i);
			value2[i] = bpList.get(i + 8);
		}
		byte[] bytes1 = new byte[bpBitWidth];
		byte[] bytes2 = new byte[bpBitWidth];
		packer.pack8Values(value1, 0, bytes1);
		baos.write(bytes1);
		packer.pack8Values(value2, 0, bytes2);
		baos.write(bytes2);

		long[] readArray = new long[16];
		byte[] bytes = new byte[2 * bpBitWidth];
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		int bytesToRead = 2 * bpBitWidth;
		bytesToRead = Math.min(bytesToRead, bais.available());
		new DataInputStream(bais).readFully(bytes, 0, bytesToRead);

		// save all long values in currentBuffer
		packer.unpackAllValues(bytes, 0, bytesToRead, readArray);
		for(int i = 0 ; i < 16 ; i++){
			long v = bpList.get(i);
			assertEquals(readArray[i], v);
		}
	}
}
