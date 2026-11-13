package com.cleanmirror;

import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

public class KeepLayerTest
{
	@Test
	public void compositesPremultipliedLayerOverFrame()
	{
		int[] frame = {0x102030, 0x102030, 0x102030};
		int[] layer = {0, 0xFFFF0000, 0x80800000}; // nothing, solid red, 50% red (premultiplied)
		KeepLayer.compositeOver(layer, frame, 3);
		assertArrayEquals(new int[]{0x102030, 0xFF0000, 0x881018}, frame);
	}

	@Test
	public void onlyTouchesFirstNPixels()
	{
		int[] frame = {1, 2, 3};
		int[] layer = {0xFF00FF00, 0xFF00FF00, 0xFF00FF00};
		KeepLayer.compositeOver(layer, frame, 2);
		assertArrayEquals(new int[]{0x00FF00, 0x00FF00, 3}, frame);
	}
}
