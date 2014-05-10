package com.gameminers.glasspane.internal;


import gminers.glasspane.GlassPane;
import gminers.glasspane.GlassPaneMirror;
import gminers.glasspane.event.KeyTypedEvent;
import gminers.glasspane.event.MouseDownEvent;
import gminers.glasspane.event.MouseUpEvent;
import gminers.glasspane.event.MouseWheelEvent;
import gminers.glasspane.event.PaneDisplayEvent;
import gminers.glasspane.event.PaneOverlayEvent;
import gminers.glasspane.event.PaneOverrideEvent;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;


/**
 * An internal class used by Glass Pane to listen to events from Forge.<br/>
 * As this is an internal class, fields and methods are likely to change without notice and without compatibility layers. Avoid directly
 * using this class if you can.
 * 
 * @author Aesen Vismea
 * 
 */
@Mod(name = "Glass Pane", modid = "GlassPane", version = "0.2 `Aluminosilicate' Beta", dependencies="required-after:KitchenSink")
@Log4j2
public class GlassPaneMod {
	@Instance("GlassPane") public static GlassPaneMod	inst;
	
	public final Map<Class<?>, GlassPane>				overrides				= Maps.newHashMap();
	public final Map<Class<?>, List<GlassPane>>			overlays				= Maps.newHashMap();
	
	public final List<GlassPane>						currentOverlays			= Lists.newCopyOnWriteArrayList();
	public final List<GlassPane>						currentStickyOverlays	= Lists.newCopyOnWriteArrayList();
	
	public final List<Class<?>>							overrideExemptions		= Lists.newCopyOnWriteArrayList();
	
	@EventHandler
	public void init(final FMLInitializationEvent e) {
		FMLCommonHandler.instance().bus().register(this);
		MinecraftForge.EVENT_BUS.register(this);
		overrideExemptions.add(GuiIngame.class);
	}
	
	public Logger getLog() {
		return log;
	}
	
	/*
	 * Gets called by Forge whenever the current GuiScreen changes.
	 * This method looks at all registered auto overlays and auto overrides, and applies them.
	 * Narrower class specs are given priority - an override for GuiMainMenu will be preferred over one for GuiScreen.
	 * For overlays, narrower class specs are put at the top of the stack, and broader at the bottom.
	 * 
	 * If you need control over where your overlay is inserted into the stack, listen for a DisplayEvent on the relevant GlassPane and
	 * directly access currentOverlays.
	 */
	@SubscribeEvent
	public void onGuiShown(final GuiOpenEvent e) {
		// first, clear the current overlays.
		currentOverlays.clear();
		// save the gui into a var, as we might be replacing it
		Object orig = e.gui;
		if (orig == null) {
			// if it's null, we're going into a game
			orig = Minecraft.getMinecraft().ingameGUI;
		}
		if (orig instanceof GlassPaneMirror) {
			// if it's a mirror, unwrap it
			orig = ((GlassPaneMirror) orig).getMirrored();
		}
		// find all applicable overrides
		final List<Entry<Class<?>, GlassPane>> possibleOverrides = Lists.newArrayList();
		boolean exempted = false;
		for (final Class<?> clazz : overrideExemptions) {
			if (clazz.isInstance(orig)) {
				exempted = true;
				break;
			}
		}
		if (!exempted) { // don't allow overriding exempted UIs
			for (final Entry<Class<?>, GlassPane> override : overrides.entrySet()) {
				if (override.getKey().isInstance(orig)) {
					if (override.getValue().getClass().isInstance(orig)) {
						continue; // don't get caught in a loop
					}
					possibleOverrides.add(override);
				}
			}
		}
		// create a hierarchy, with Object having the highest index
		final List<Class<?>> hierarchy = Lists.newArrayList();
		Class<?> work = orig.getClass();
		while (true) {
			hierarchy.add(work);
			if (work.getSuperclass() == null) {
				break;
			}
			work = work.getSuperclass();
		}
		// create a comparator using the hierarchy we created
		final Comparator<Entry<Class<?>, ?>> classComparator = new Comparator<Entry<Class<?>, ?>>() {
			@Override
			public int compare(final Entry<Class<?>, ?> o1, final Entry<Class<?>, ?> o2) {
				return hierarchy.indexOf(o1.getKey()) - hierarchy.indexOf(o2.getKey());
			}
		};
		// if we found multiple overrides, do some sort-y stuff
		if (possibleOverrides.size() > 1) {
			// sort the overrides so that the first object has the most narrow class specification
			Collections.sort(possibleOverrides, classComparator);
			// and apply index 0
			e.gui = possibleOverrides.get(0).getValue().getScreenMirror();
			final GlassPane pane = possibleOverrides.get(0).getValue();
			pane.fireEvent(PaneDisplayEvent.class, pane);
			pane.fireEvent(PaneOverrideEvent.class, pane, orig);
		} else if (possibleOverrides.size() == 1) {
			// if there's only one possibility, apply it without further ado
			e.gui = possibleOverrides.get(0).getValue().getScreenMirror();
			final GlassPane pane = possibleOverrides.get(0).getValue();
			pane.fireEvent(PaneDisplayEvent.class, pane);
			pane.fireEvent(PaneOverrideEvent.class, pane, orig);
		}
		// we do the possible overlays list here instead of when we do overrides so that we can properly react to an override being applied
		Object newGui = e.gui;
		if (newGui == null) {
			// if it's null, we're going into a game
			newGui = Minecraft.getMinecraft().ingameGUI;
		}
		if (newGui instanceof GlassPaneMirror) {
			// if it's a mirror, unwrap it
			newGui = ((GlassPaneMirror) newGui).getMirrored();
		}
		final List<Entry<Class<?>, List<GlassPane>>> possibleOverlays = Lists.newArrayList();
		for (final Entry<Class<?>, List<GlassPane>> overlay : overlays.entrySet()) {
			if (overlay.getKey().isInstance(orig) || overlay.getKey().isInstance(newGui)) {
				possibleOverlays.add(overlay);
			}
		}
		final List<GlassPane> applyingOverlays = Lists.newArrayList();
		// sort the overlays so that the first object has the most narrow class specification
		Collections.sort(possibleOverlays, Collections.reverseOrder(classComparator));
		// flatten the "map"
		for (final Entry<Class<?>, List<GlassPane>> overlay : possibleOverlays) {
			applyingOverlays.addAll(overlay.getValue());
		}
		// apply the overlays
		if (applyingOverlays.size() >= 1) {
			final ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft().gameSettings,
					Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight);
			for (final GlassPane pane : applyingOverlays) {
				pane.setWidth(res.getScaledWidth());
				pane.setHeight(res.getScaledHeight());
				currentOverlays.add(pane);
				pane.fireEvent(PaneOverlayEvent.class, pane);
			}
		}
	}
	
	private int	touchScreenCounter;
	
	@SubscribeEvent
	@SneakyThrows
	public void onTick(final TickEvent.ClientTickEvent e) {
		if (e.phase == TickEvent.Phase.START) {
			// this is a terrible, terrible hack, but there's no better way to do it
			// Mouse and Keyboard InputEvents are only called when in a game, not a GUI, which is the opposite of helpful
			// so we do this awful hack
			final Minecraft mc = Minecraft.getMinecraft();
			final ScaledResolution res = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
			final int width = res.getScaledWidth();
			final int height = res.getScaledHeight();
			// that wasn't the hack - see the reflection below
			if (Mouse.isCreated()) {
				final Class<Mouse> mouseClazz = Mouse.class;
				final Field f = mouseClazz.getDeclaredField("readBuffer");
				f.setAccessible(true);
				final ByteBuffer buf = (ByteBuffer) f.get(null);
				buf.mark();
				while (Mouse.next()) {
					final int mX = Mouse.getEventX() * width / mc.displayWidth;
					final int mY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
					int button = Mouse.getEventButton();
					
					if (Minecraft.isRunningOnMac && button == 0
							&& (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
						button = 1;
					}
					
					if (Mouse.getEventButtonState()) {
						if (mc.gameSettings.touchscreen && touchScreenCounter++ > 0) {
							return;
						}
						
						for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
							pane.fireEvent(MouseDownEvent.class, pane, mX, mY, button);
						}
					} else if (button != -1) {
						if (mc.gameSettings.touchscreen && --touchScreenCounter > 0) {
							return;
						}
						if (mc.currentScreen instanceof GlassPaneMirror) {
							final GlassPane pane = ((GlassPaneMirror) mc.currentScreen).getMirrored();
							pane.fireEvent(MouseUpEvent.class, pane, mX, mY, button);
						}
						for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
							pane.fireEvent(MouseUpEvent.class, pane, mX, mY, button);
						}
					}
				}
				if (Mouse.hasWheel()) {
					final int wheel = Mouse.getDWheel();
					if (wheel != 0) {
						final int mX = Mouse.getX() * width / mc.displayWidth;
						final int mY = height - Mouse.getY() * height / mc.displayHeight - 1;
						if (mc.currentScreen instanceof GlassPaneMirror) {
							final GlassPane pane = ((GlassPaneMirror) mc.currentScreen).getMirrored();
							pane.fireEvent(MouseWheelEvent.class, pane, mX, mY, wheel);
						}
						for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
							pane.fireEvent(MouseWheelEvent.class, pane, mX, mY, wheel);
						}
						final Field wheelF = mouseClazz.getDeclaredField("dwheel");
						wheelF.setAccessible(true);
						wheelF.set(null, wheel);
					}
				}
				buf.reset();
			}
			
			if (Keyboard.isCreated()) {
				final Class<Keyboard> keyboardClazz = Keyboard.class;
				final Field f = keyboardClazz.getDeclaredField("readBuffer");
				f.setAccessible(true);
				final ByteBuffer buf = (ByteBuffer) f.get(null);
				buf.mark();
				while (Keyboard.next()) {
					if (Keyboard.getEventKeyState()) {
						final int kCode = Keyboard.getEventKey();
						final char kChar = Keyboard.getEventCharacter();
						
						for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
							pane.fireEvent(KeyTypedEvent.class, pane, kChar, kCode);
						}
					}
				}
				buf.reset();
			}
		} else if (e.phase == TickEvent.Phase.END) {
			// get the minecraft instance
			final Minecraft mc = Minecraft.getMinecraft();
			// get the resolution
			final ScaledResolution res = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
			// store width and height for convenience
			final int width = res.getScaledWidth();
			final int height = res.getScaledHeight();
			// reset mouse's dWheel
			Mouse.getDWheel();
			// iterate through the current overlays
			for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
				// if the pane's width is out of sync, sync it
				if (pane.getWidth() != width) {
					pane.setWidth(width);
				}
				// if the pane's height is out of sync, sync it
				if (pane.getHeight() != height) {
					pane.setHeight(height);
				}
				// tick the pane
				pane.tick();
				// tick the pane's shadowbox, if present
				if (pane.getShadowbox() != null) {
					pane.getShadowbox().tick();
				}
			}
		}
	}
	
	@SubscribeEvent
	public void onRender(final TickEvent.RenderTickEvent e) {
		if (e.phase == TickEvent.Phase.END) {
			final Minecraft mc = Minecraft.getMinecraft();
			final ScaledResolution res = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
			// just render all the overlays in insertion order
			for (final GlassPane pane : combine(currentOverlays, currentStickyOverlays)) {
				// clear the depth buffer to make overlays always render in front
				GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
				// have to do weird maths with the mouse stuff because Minecraft's 0, 0 is top-left, but GL/LWJGL's 0, 0 is bottom-left, and
				// since Minecraft does resolution scaling
				pane.render(Mouse.getX() * res.getScaledWidth() / mc.displayWidth, res.getScaledHeight() - Mouse.getY()
						* res.getScaledHeight() / mc.displayHeight - 1, e.renderTickTime);
			}
		}
	}
	
	private <T> List<T> combine(final List<T> a, final List<T> b) {
		final List<T> list = Lists.newArrayList();
		list.addAll(a);
		list.addAll(b);
		return list;
	}
}
