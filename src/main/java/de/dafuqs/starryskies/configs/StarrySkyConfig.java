package de.dafuqs.starryskies.configs;

import me.shedaniel.autoconfig.*;
import me.shedaniel.autoconfig.annotation.*;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.*;

@Config(name = "StarrySky")
public class StarrySkyConfig implements ConfigData {

	@ConfigEntry.Category("GENERAL")
	@Comment(value = """
			The amount of chunks each sphere system spans.
			Higher values make spheres spread out farther, having more air in between
			Default: 50""")
	public int systemSizeChunks = 50;

	@ConfigEntry.Gui.Tooltip()
	@ConfigEntry.Category("GENERAL")
	@Comment(value = """
			The '/starryskies_locate' command lists all the data of the closest sphere (position, blocks, ...)
			Default: 2""")
	public int locateSphereCommandRequiredPermissionLevel = 2;

	@ConfigEntry.Gui.Tooltip()
	@ConfigEntry.Category("GENERAL")
	@Comment(value = """
			The '/starryskies_system_statistics' command lists the amount of generated spheres listed by id)
			Default: 2""")
	public int systemStatisticsCommandRequiredPermissionLevel = 2;

	@ConfigEntry.Gui.Tooltip()
	@ConfigEntry.Category("GENERAL")
	@Comment(value = """
			The '/starryskies_generate' command lets users generate new spheres
			Default: 4""")
	public int generateSphereCommandRequiredPermissionLevel = 4;

}
