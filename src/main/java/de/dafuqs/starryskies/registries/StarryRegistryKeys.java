package de.dafuqs.starryskies.registries;

import de.dafuqs.starryskies.*;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.registry.*;

public class StarryRegistryKeys {

	// Builtin Registries
	public static final RegistryKey<Registry<Sphere<?>>> SPHERE = of("sphere_type");
	public static final RegistryKey<Registry<SphereDecorator<?>>> SPHERE_DECORATOR = of("sphere_decorator");

	// Dynamic Registries
	public static final RegistryKey<Registry<ConfiguredSphereDecorator<?, ?>>> CONFIGURED_SPHERE_DECORATOR = of(
			"configured_decorator");
	public static final RegistryKey<Registry<ConfiguredSphere<?, ?>>> CONFIGURED_SPHERE = of("configured_sphere");

	private static <T> RegistryKey<Registry<T>> of(String name) {
		return RegistryKey.ofRegistry(StarrySkies.id(name));
	}

}
