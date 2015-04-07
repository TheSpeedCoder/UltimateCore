/*
 * This file is part of UltimateCore, licensed under the MIT License (MIT).
 *
 * Copyright (c) Bammerbom
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package bammerbom.ultimatecore.spongeapi;

import bammerbom.ultimatecore.spongeapi.configuration.Config;
import bammerbom.ultimatecore.spongeapi.resources.classes.ErrorLogger;
import org.bukkit.Bukkit;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;

public class UltimateWorldLoader {

    public static void startWorldLoading() {
        Config conf = new Config(UltimateFileLoader.DFworlds);
        for (String str : conf.getKeys(false)) {
            try {
                WorldCreator w = new WorldCreator(str);
                if (conf.contains(str + ".env")) {
                    w.environment(Environment.valueOf(conf.getString(str + ".env")));
                    Bukkit.createWorld(w);
                }
            } catch (Exception ex) {
                r.log("Failed to load world " + str);
                ErrorLogger.log(ex, "Failed to load world " + str);
            }
        }
    }
}