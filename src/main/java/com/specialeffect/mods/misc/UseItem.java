/**
 * Copyright (C) 2016 Kirsty McNaught, SpecialEffect
 * www.specialeffect.org.uk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 */

package com.specialeffect.mods.misc;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.irtimaled.bbor.client.renderers.AbstractRenderer;

import com.specialeffect.utils.ChildModWithConfig;
import com.specialeffect.mods.ChildMod;
import com.specialeffect.mods.EyeMineConfig;
import com.specialeffect.mods.mousehandling.MouseHandler;
import com.specialeffect.mods.utils.KeyWatcher;
import com.specialeffect.utils.CommonStrings;
import com.specialeffect.utils.ModUtils;

import net.minecraft.block.LadderBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.InputEvent.KeyInputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.client.model.generators.ItemModelBuilder;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public class UseItem 
extends ChildMod implements ChildModWithConfig {
	public final String MODID = "useitem";

	public void setup(final FMLCommonSetupEvent event) {

		// Register key bindings
		mUseItemOnceKB = new KeyBinding("Use item", GLFW.GLFW_KEY_KP_0, CommonStrings.EYEGAZE_COMMON);
		ClientRegistry.registerKeyBinding(mUseItemOnceKB);

		mUseItemContinuouslyKB = new KeyBinding("Use item continuously", GLFW.GLFW_KEY_KP_1,
				CommonStrings.EYEGAZE_EXTRA);
		ClientRegistry.registerKeyBinding(mUseItemContinuouslyKB);

		mPrevItemKB = new KeyBinding("Select previous item", GLFW.GLFW_KEY_KP_4, CommonStrings.EYEGAZE_EXTRA);
		ClientRegistry.registerKeyBinding(mPrevItemKB);

		mNextItemKB = new KeyBinding("Select next item", GLFW.GLFW_KEY_KP_5, CommonStrings.EYEGAZE_EXTRA);
		ClientRegistry.registerKeyBinding(mNextItemKB);

		this.syncConfig();
	}

	private static KeyBinding mUseItemOnceKB;
	private static KeyBinding mUseItemContinuouslyKB;
	private static KeyBinding mPrevItemKB;
	private static KeyBinding mNextItemKB;
	
	// State for 'continuously use'
	private boolean mUsingItem = false;
	private boolean mDwelling = false;
	
	private long lastTime = 0;
	private int dwellTimeInit = 200; // ms
	private int dwellTimeComplete = 1000; // ms
	private int dwellTimeDecay = 200;
	
	// State for firing bows
	private int bowTime = 2000; //ms
	private int bowCountdown = 0;
	private boolean needBowFire = false;
	
	private Map<TargetBlock, DwellState> liveTargets = new HashMap<>();

	public void syncConfig() {
        this.dwellTimeComplete = (int) (1000*EyeMineConfig.dwellTimeSeconds.get());
        this.dwellTimeInit = (int) (1000*EyeMineConfig.dwellLockonTimeSeconds.get());
        this.dwellTimeDecay = this.dwellTimeComplete/5;
        this.bowTime = (int) (1000*EyeMineConfig.bowDrawTime.get());
	}
	
	@SubscribeEvent
	public void onClientTick(ClientTickEvent event) {
		if (event.phase == Phase.START) {
			long time = System.currentTimeMillis();
			long dt = time - this.lastTime;
			this.lastTime = time;			
			
			this.dwellTimeDecay = 300;
			
			// Behaviour for shootable items (bows)
			if (this.bowCountdown > 0 ) {
				this.bowCountdown -= dt;
				if (this.bowCountdown < 1) {
					// Release bow if count-down complete
					final KeyBinding useItemKeyBinding = Minecraft.getInstance().gameSettings.keyBindUseItem;
					KeyBinding.setKeyBindState(useItemKeyBinding.getKey(), false);
					
					// If it was a crossbow we'll need to re-click to actually fire it
					PlayerEntity player = Minecraft.getInstance().player;					
					Item item = player.inventory.getCurrentItem().getItem();
					if (item instanceof CrossbowItem) {
						// Crossbows don't fire on mouse-release, they need another 'click' on the next tick to be shot
						needBowFire = true;
					}
				}
			}
			else {
				if (needBowFire) {
					final KeyBinding useItemKeyBinding = Minecraft.getInstance().gameSettings.keyBindUseItem;
					KeyBinding.onTick(useItemKeyBinding.getKey());
					needBowFire = false;
				}
			}
			
			if (mDwelling && !ModUtils.hasActiveGui()) {
				if (MouseHandler.hasPendingEvent() || EyeMineConfig.moveWhenMouseStationary.get()) {

					// What are we currently targeting?
					BlockRayTraceResult rayTraceBlock = ModUtils.getMouseOverBlock(); 							
					TargetBlock currentTarget = (rayTraceBlock == null) ? null : new TargetBlock(rayTraceBlock);	
					
					// If it's not in the hashmap yet, it means we hit here before the render event - just wait until next tick
					if (!liveTargets.containsKey(currentTarget)) {
						return;
					}
					
					// Update all dwell times: the current target increments, others decrement and decay
					for (Map.Entry<TargetBlock, DwellState> entry : liveTargets.entrySet()) {
						TargetBlock key = entry.getKey();						
						boolean active = key.equals(currentTarget);
						DwellState dwellState = entry.getValue(); 
					    dwellState.update(dt, active);
					}
					
					// Remove all that have decayed fully
					liveTargets.entrySet().removeIf(e -> e.getValue().shouldDiscard());
					
					// Place block if dwell complete	
					if (currentTarget != null && liveTargets.get(currentTarget).hasCompleted()) {						
						final KeyBinding useItemKeyBinding = Minecraft.getInstance().gameSettings.keyBindUseItem;
						KeyBinding.onTick(useItemKeyBinding.getKey());
						liveTargets.remove(currentTarget);		
					}
					// TODO: what if can't use item ? Currently this results in flashing again and again
					// Add this to dwell state?
				}
			}
		}
	}
	
	private void renderCentralisedDwell(TargetBlock target, DwellState dwellState, boolean expanding) {
		Color color = new Color(0.75f, 0.25f, 0.0f);

		// size of square proportional to dwell progress
		double dDwell = dwellState.getDwellProportionSinceLockon();
		
		// opacity proportional to decay progress
		int usualOpacity = 125;
		int iOpacity = (int) (usualOpacity*(0.25F + 0.75F*(1.0f - dwellState.getDecayProportion())));
		
		AbstractRenderer.renderBlockFaceCentralisedDwell(target.pos, target.direction, color, dDwell, iOpacity);	
	}
	
	private void renderOpacityDwell(TargetBlock target, DwellState dwellState) {
		Color color = new Color(0.75f, 0.25f, 0.0f);
		double maxAlpha = 0.85*255.0; 
		
		// Opacity increases with dwell amount
		double dAlpha = maxAlpha*(dwellState.getDwellProportion());
		int iAlpha = (int)dAlpha;
		
		AbstractRenderer.renderBlockFace(target.pos, target.direction, color, iAlpha);
	}
	
	@SubscribeEvent
	public void onBlockOutlineRender(DrawBlockHighlightEvent e)
	{
				
		if (Minecraft.getInstance().currentScreen != null) {
			this.liveTargets.clear();
			return;
		}
						
		if (mDwelling) {
			
			// Add current block to live targets if required
			// (we only want to add from within this method, so we avoid floating un-buildable surfaces, 
			// a.k.a. "MISS" ray trace results)
			if (e.getTarget().getType() == RayTraceResult.Type.MISS) {
				return;
			}
			
			BlockRayTraceResult rayTraceBlock = ModUtils.getMouseOverBlock();
			TargetBlock currentTarget = (rayTraceBlock == null) ? null : new TargetBlock(rayTraceBlock);
			if (currentTarget != null && !liveTargets.containsKey(currentTarget)) {
				liveTargets.put(currentTarget, 
						new DwellState(this.dwellTimeComplete, this.dwellTimeInit, this.dwellTimeDecay));
			}
			
			// Update dwell visualisation for all targets
			for (Map.Entry<TargetBlock, DwellState> entry : liveTargets.entrySet()) {
										
				DwellState dwellState = entry.getValue(); 				
				if (dwellState.shouldRender()) {
					TargetBlock target = entry.getKey();
					
					boolean doCentralised = !EyeMineConfig.dwellShowWithTransparency.get();
					boolean expanding = EyeMineConfig.dwellShowExpanding.get();
					
					if (doCentralised) {
						this.renderCentralisedDwell(target, dwellState, expanding);	
					}
					else {
						this.renderOpacityDwell(target, dwellState);
					}
				}
			}
		}
	}
	
	@SubscribeEvent
	public void onKeyInput(KeyInputEvent event) {
		
		if (ModUtils.hasActiveGui()) { return; }	    
	    if (event.getAction() != GLFW.GLFW_PRESS) { return; }

	    if (KeyWatcher.f3Pressed) { return; }

		final KeyBinding useItemKeyBinding = Minecraft.getInstance().gameSettings.keyBindUseItem;
		PlayerEntity player = Minecraft.getInstance().player;
		
		if (event.getKey() == mUseItemContinuouslyKB.getKey().getKeyCode()) {
			
			if (mUsingItem || mDwelling) {
				// Turn off whatever
				final String message = (mUsingItem ? "Using item: " : "Dwell building: ") + "OFF";			
		        player.sendMessage(new StringTextComponent(message));	       

				mUsingItem = false;
				mDwelling = false;
				this.liveTargets.clear();			
				KeyBinding.setKeyBindState(useItemKeyBinding.getKey(), mUsingItem);
			}
			else {
				// Turn on either dwell build or continuous-building
						
				ItemStack itemStack = player.inventory.getCurrentItem();
				if (itemStack == null || itemStack.getItem() == null) {
			        player.sendMessage(new StringTextComponent("Nothing in hand to use"));
			        return;
				}
		        Item item = player.inventory.getCurrentItem().getItem();			
				
				if (item instanceof BlockItem && !(((BlockItem) item).getBlock() instanceof LadderBlock)) {
					// All blocks except ladders use dwell building
					mDwelling = true;					
				}
				else {
					// Non-blocks (e.g. food, tools) use continuous-use
					mUsingItem = true;				
					KeyBinding.setKeyBindState(useItemKeyBinding.getKey(), mUsingItem);
				}
				
				final String message = (mUsingItem ? "Using item: " : "Dwell building: ") + "ON";				
		        player.sendMessage(new StringTextComponent(message));	       
			}
		} else if (mUseItemOnceKB.getKey().getKeyCode() == event.getKey()) {
			
			
			
			ItemStack stack = player.inventory.getCurrentItem();
			Item item = stack.getItem();
			
			// Special case for shootable items			
			if (item instanceof CrossbowItem) {
				// Crossbows need charging separately to firing. If already charged, shoot it. 
				// Otherwise start chargin. 				
				if (CrossbowItem.isCharged(stack)) {
					KeyBinding.onTick(useItemKeyBinding.getKey());					
				}						
				else {
					int crossbowTime = 1500;
					ModUtils.sendPlayerMessage("Firing bow");
					KeyBinding.setKeyBindState(useItemKeyBinding.getKey(), true);
					this.bowCountdown = Math.max(crossbowTime, this.bowTime);
				}
			}
			else if (item instanceof BowItem) {
				// Bows need charging + firing all in one go			
				this.bowTime = 1500;
				ModUtils.sendPlayerMessage("Firing bow");
				KeyBinding.setKeyBindState(useItemKeyBinding.getKey(), true);
				this.bowCountdown = this.bowTime;							
			}
			else {
				KeyBinding.onTick(useItemKeyBinding.getKey());	
			}			
			
		} else if (mPrevItemKB.getKey().getKeyCode() == event.getKey()) {
			player.inventory.changeCurrentItem(1);
		} else if (mNextItemKB.getKey().getKeyCode() == event.getKey()) {
			player.inventory.changeCurrentItem(-1);
		}
	}
	
	@SubscribeEvent
	public void onRenderGameOverlayEvent(final RenderGameOverlayEvent.Post event) {
		if(event.getType() != ElementType.EXPERIENCE)
		{      
			return;
		}
		
		// If use-item is on, show a warning message
		String msg = "";
		if (mUsingItem) {
			msg = "USING";
		}
		if (mDwelling) {
			msg = "DWELLING";
		}
		
		if (!msg.isEmpty()) {
			Minecraft mc = Minecraft.getInstance();
			int w = mc.mainWindow.getScaledWidth();
			int h = mc.mainWindow.getScaledHeight();
						
			int msgWidth = mc.fontRenderer.getStringWidth(msg);
		    
		    mc.fontRenderer.drawStringWithShadow(msg, w/2 - msgWidth/2, h/2 - 20, 0xffFFFFFF);		    
		    
		}
		
	}
}
