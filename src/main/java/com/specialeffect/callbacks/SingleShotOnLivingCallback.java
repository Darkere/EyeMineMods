/**
 * Copyright (C) 2016 Kirsty McNaught, SpecialEffect
 * www.specialeffect.org.uk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 */

package com.specialeffect.callbacks;

import net.minecraftforge.event.TickEvent.ClientTickEvent;

public class SingleShotOnLivingCallback implements OnLivingCallback {

	public SingleShotOnLivingCallback(IOnLiving callback) {
		mCallback = callback;
	}
	
	@Override
	public void onClientTick(ClientTickEvent event) {
		mCallback.onClientTick(event);
		mHasCompleted = true;
	}

	@Override
	public boolean hasCompleted() {
		return mHasCompleted;
	}
	
	boolean mHasCompleted = false;
	IOnLiving mCallback;
}
