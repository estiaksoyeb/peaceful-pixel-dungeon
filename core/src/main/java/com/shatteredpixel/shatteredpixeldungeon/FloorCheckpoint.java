/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.FileUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class FloorCheckpoint {

	public static class CheckpointData {
		public final int depth;
		public final int branch;
		public final boolean ascending;
		public final Bundle dungeonBundle;
		public final Bundle levelBundle;

		public CheckpointData(int depth, int branch, boolean ascending, Bundle dungeonBundle, Bundle levelBundle) {
			this.depth = depth;
			this.branch = branch;
			this.ascending = ascending;
			this.dungeonBundle = dungeonBundle;
			this.levelBundle = levelBundle;
		}
	}

	private static final Map<Integer, CheckpointData> memoryCheckpoints = new HashMap<>();
	public static boolean isRewinding = false;
	public static String lastDeathCause = null;

	public static boolean isEnabled() {
		return SPDSettings.floorRewind();
	}

	public static boolean isAscending() {
		return Dungeon.hero != null && Dungeon.hero.buff(AscensionChallenge.class) != null;
	}

	public static int checkpointKey(int slot, int depth, int branch, boolean ascending) {
		return slot * 1000000 + depth + 1000 * branch + (ascending ? 100000 : 0);
	}

	public static void clear() {
		memoryCheckpoints.clear();
		isRewinding = false;
		lastDeathCause = null;
	}

	public static boolean hasCheckpoint(int depth, int branch) {
		return hasCheckpoint(GamesInProgress.curSlot, depth, branch, isAscending());
	}

	public static boolean hasCheckpoint(int slot, int depth, int branch) {
		return hasCheckpoint(slot, depth, branch, isAscending());
	}

	public static boolean hasCheckpoint(int slot, int depth, int branch, boolean ascending) {
		int id = checkpointKey(slot, depth, branch, ascending);
		if (memoryCheckpoints.containsKey(id)) {
			return true;
		}
		String file = GamesInProgress.checkpointFile(slot, depth, branch, ascending);
		return FileUtils.fileExists(file);
	}

	public static void onFloorEntered(int depth, int branch) {
		if (!isEnabled() || isRewinding) {
			return;
		}

		boolean ascending = isAscending();
		createCheckpoint(GamesInProgress.curSlot, depth, branch, ascending);
	}

	public static void createCheckpoint(int depth, int branch, boolean ascending) {
		createCheckpoint(GamesInProgress.curSlot, depth, branch, ascending);
	}

	public static void createCheckpoint(int slot, int depth, int branch, boolean ascending) {
		try {
			Bundle dungeonBundle = Dungeon.gameToBundle();
			Bundle levelBundle = new Bundle();
			levelBundle.put(Dungeon.LEVEL, Dungeon.level);

			CheckpointData cp = new CheckpointData(depth, branch, ascending, dungeonBundle, levelBundle);
			memoryCheckpoints.clear();
			memoryCheckpoints.put(checkpointKey(slot, depth, branch, ascending), cp);

			Bundle fileBundle = new Bundle();
			fileBundle.put("depth", depth);
			fileBundle.put("branch", branch);
			fileBundle.put("ascending", ascending);
			fileBundle.put("dungeon", dungeonBundle);
			fileBundle.put("level", levelBundle);

			String file = GamesInProgress.checkpointFile(slot, depth, branch, ascending);
			FileUtils.bundleToFile(file, fileBundle);
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
		}
	}

	public static CheckpointData getCheckpoint(int depth, int branch, boolean ascending) {
		return getCheckpoint(GamesInProgress.curSlot, depth, branch, ascending);
	}

	public static CheckpointData getCheckpoint(int slot, int depth, int branch, boolean ascending) {
		int id = checkpointKey(slot, depth, branch, ascending);
		CheckpointData cp = memoryCheckpoints.get(id);
		if (cp != null) {
			return cp;
		}

		String file = GamesInProgress.checkpointFile(slot, depth, branch, ascending);
		if (FileUtils.fileExists(file)) {
			try {
				Bundle fileBundle = FileUtils.bundleFromFile(file);
				int d = fileBundle.getInt("depth");
				int b = fileBundle.getInt("branch");
				boolean asc = fileBundle.getBoolean("ascending");
				Bundle dungeonBundle = fileBundle.getBundle("dungeon");
				Bundle levelBundle = fileBundle.getBundle("level");
				cp = new CheckpointData(d, b, asc, dungeonBundle, levelBundle);
				memoryCheckpoints.put(id, cp);
				return cp;
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
			}
		}

		return null;
	}

	public static void rewind() {
		rewind(null);
	}

	public static void rewind(Object cause) {
		if (isRewinding) {
			return;
		}
		isRewinding = true;

		if (cause != null) {
			if (cause instanceof String) {
				lastDeathCause = (String) cause;
			} else {
				Class<?> causeClass = cause instanceof Class ? (Class<?>) cause : cause.getClass();
				String name = Messages.get(causeClass, "name");
				String desc = Messages.get(causeClass, "rankings_desc", name);
				if (desc.contains(Messages.NO_TEXT_FOUND)) {
					if (cause instanceof Char) {
						lastDeathCause = ((Char) cause).name();
					} else {
						lastDeathCause = null;
					}
				} else {
					lastDeathCause = desc;
				}
			}
		} else {
			lastDeathCause = null;
		}

		Actor.fixTime();
		if (Dungeon.hero != null) {
			Dungeon.hero.curAction = null;
			Dungeon.hero.interrupt();
		}

		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				InterlevelScene.mode = InterlevelScene.Mode.REWIND;
				Game.switchScene(InterlevelScene.class);
			}
		});
	}

	public static boolean restore(int slot, int depth, int branch) throws IOException {
		boolean ascending = isAscending();
		CheckpointData cp = getCheckpoint(slot, depth, branch, ascending);
		if (cp == null && !ascending) {
			cp = getCheckpoint(slot, depth, branch, false);
		}
		if (cp == null) {
			isRewinding = false;
			return false;
		}

		// 1. Restore run and hero state from bundle
		Dungeon.bundleToGame(cp.dungeonBundle, true);

		// 2. Restore level from checkpoint level bundle
		Dungeon.level = null;
		Level level = (Level) cp.levelBundle.get(Dungeon.LEVEL);
		if (level == null) {
			isRewinding = false;
			throw new IOException("Failed to deserialize checkpoint level");
		}

		// 3. Switch to restored level
		Dungeon.switchLevel(level, Dungeon.hero.pos);

		return true;
	}
}
