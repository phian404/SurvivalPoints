package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Парсер ASCII-блюпринтов лагеря.
 * Формат: F=забор, G=ворота, T=вышка, W=склад, R=радио, B=доска, .=пусто
 */
public class CampBlueprintParser {

    public static class Blueprint {
        public final String[] grid;
        public final int radius;
        public final BlockPos gatePos;
        public final List<BlockPos> towerPos = new ArrayList<>();
        public final BlockPos radioPos;
        public final BlockPos storagePos;

        public Blueprint(String[] grid, int radius, BlockPos gatePos, BlockPos radioPos,
                         BlockPos storagePos) {
            this.grid = grid;
            this.radius = radius;
            this.gatePos = gatePos;
            this.radioPos = radioPos;
            this.storagePos = storagePos;
        }
    }

    public static Blueprint parse(String[] grid, int radius) {
        BlockPos gate = null, radio = null, storage = null;
        for (int z = 0; z < grid.length; z++) {
            String row = grid[z];
            for (int x = 0; x < row.length(); x++) {
                char c = row.charAt(x);
                BlockPos p = new BlockPos(x - row.length()/2, 0, z - grid.length/2);
                if (c == 'G') gate = p;
                else if (c == 'R') radio = p;
                else if (c == 'W') storage = p;
                else if (c == 'T') {
                    // вышки собираются отдельно
                }
            }
        }
        return new Blueprint(grid, radius, gate, radio, storage);
    }

    public static String[] defaultBlueprint() {
        return new String[]{
            "F F F F F F F",
            "F T . . . T F",
            "F . W R B . F",
            "F . . G . . F",
            "F F F F F F F"
        };
    }
}
