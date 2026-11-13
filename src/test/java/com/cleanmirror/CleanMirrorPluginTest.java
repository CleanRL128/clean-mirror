package com.cleanmirror;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite with Clean Mirror loaded, for local testing (./gradlew run). */
public class CleanMirrorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CleanMirrorPlugin.class);
		RuneLite.main(args);
	}
}
