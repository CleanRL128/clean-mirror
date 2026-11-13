package com.cleanmirror;

public enum MirrorScale
{
	X1("1x", 1.0),
	X1_5("1.5x", 1.5),
	X2("2x", 2.0),
	FIT("Fit to window", 0);

	private final String label;
	/** Fixed scale factor, or 0 to fit the window. */
	final double factor;

	MirrorScale(String label, double factor)
	{
		this.label = label;
		this.factor = factor;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
