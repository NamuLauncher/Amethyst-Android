package com.oracle.dalvik;

import android.content.Context;

public final class VMLauncher {
	private VMLauncher() {
	}
	public static native int launchJVM(String[] args);

	public static native int launchWithAuthYou(
			Context applicationContext,
			String[] args,
			String serverId,
			String userId,
			String host,
			String baseDirectory,
			String fileSetId
	);
}
