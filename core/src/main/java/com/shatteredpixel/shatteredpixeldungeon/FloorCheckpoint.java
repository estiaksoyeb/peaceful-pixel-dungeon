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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.FileUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	public static boolean isEnabled() {
		return SPDSettings.floorRewind();
	}

	public static boolean isAscending() {
		return Dungeon.hero != null && Dungeon.hero.buff(AscensionChallenge.class) != null;
	}

	public static int floorId(int depth, int branch, boolean ascending) {
		return depth + 1000 * branch + (ascending ? 100000 : 0);
	}

	public static void clear() {
		memoryCheckpoints.clear();
		isRewinding = false;
	}

	public static boolean hasCheckpoint(int depth, int branch) {
		return hasCheckpoint(depth, branch, isAscending());
	}

	public static boolean hasCheckpoint(int depth, int branch, boolean ascending) {
		int id = floorId(depth, branch, ascending);
		if (memoryCheckpoints.containsKey(id)) {
			return true;
		}
		String file = GamesInProgress.checkpointFile(GamesInProgress.curSlot, depth, branch, ascending);
		return FileUtils.fileExists(file);
	}

	public static void onFloorEntered(int depth, int branch) {
		if (!isEnabled()) {
			return;
		}

		boolean ascending = isAscending();
		if (hasCheckpoint(depth, branch, ascending)) {
			return;
		}

		createCheckpoint(depth, branch, ascending);
	}

	public static void createCheckpoint(int depth, int branch, boolean ascending) {
		try {
			Bundle dungeonBundle = Dungeon.gameToBundle();
			Bundle levelBundle = new Bundle();
			levelBundle.put(Dungeon.LEVEL, Dungeon.level);

			CheckpointData cp = new CheckpointData(depth, branch, ascending, dungeonBundle, levelBundle);
			memoryCheckpoints.put(floorId(depth, branch, ascending), cp);

			Bundle fileBundle = new Bundle();
			fileBundle.put("depth", depth);
			fileBundle.put("branch", branch);
			fileBundle.put("ascending", ascending);
			fileBundle.put("dungeon", dungeonBundle);
			fileBundle.put("level", levelBundle);

			String file = GamesInProgress.checkpointFile(GamesInProgress.curSlot, depth, branch, ascending);
			FileUtils.bundleToFile(file, fileBundle);
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
		}
	}

	public static CheckpointData getCheckpoint(int depth, int branch, boolean ascending) {
		int id = floorId(depth, branch, ascending);
		CheckpointData cp = memoryCheckpoints.get(id);
		if (cp != null) {
			return cp;
		}

		String file = GamesInProgress.checkpointFile(GamesInProgress.curSlot, depth, branch, ascending);
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
		if (isRewinding) {
			return;
		}
		isRewinding = true;

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
		CheckpointData cp = getCheckpoint(depth, branch, ascending);
		if (cp == null && !ascending) {
			cp = getCheckpoint(depth, branch, false);
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

		// 3. Clean up future levels and checkpoints that occurred after this checkpoint
		cleanFutureLevelsAndCheckpoints(slot);

		// 4. Switch to restored level
		Dungeon.switchLevel(level, Dungeon.hero.pos);

		return true;
	}

	public static void cleanFutureLevelsAndCheckpoints(int slot) {
		String folder = GamesInProgress.gameFolder(slot);
		ArrayList<String> files = FileUtils.filesInDir(folder);

		// Matches depth%d.dat or depth%d-branch%d.dat
		Pattern depthPattern = Pattern.compile("^depth(\\d+)(?:-branch(\\d+))?\\.dat$");
		// Matches checkpoint_depth%d.dat or checkpoint_depth%d-branch%d.dat or with _ascend
		Pattern checkpointPattern = Pattern.compile("^checkpoint_depth(\\d+)(?:-branch(\\d+))?(?:_ascend)?\\.dat$");

		for (String fileName : files) {
			Matcher m = depthPattern.matcher(fileName);
			if (m.matches()) {
				int d = Integer.parseInt(m.group(1));
				int b = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
				if (!Dungeon.levelHasBeenGenerated(d, b)) {
					FileUtils.deleteFile(folder + "/" + fileName);
				}
				continue;
			}

			Matcher cm = checkpointPattern.matcher(fileName);
			if (cm.matches()) {
				int d = Integer.parseInt(cm.group(1));
				int b = cm.group(2) != null ? Integer.parseInt(cm.group(2)) : 0;
				boolean isAsc = fileName.contains("_ascend");
				if (!Dungeon.levelHasBeenGenerated(d, b)) {
					FileUtils.deleteFile(folder + "/" + fileName);
					memoryCheckpoints.remove(floorId(d, b, isAsc));
				}
			}
		}

		memoryCheckpoints.keySet().removeIf(id -> {
			int baseFloor = id % 100000;
			int d = baseFloor % 1000;
			int b = baseFloor / 1000;
			return !Dungeon.levelHasBeenGenerated(d, b);
		});
	}
}
