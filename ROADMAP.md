# Roadmap

What is planned, and roughly in what order. No dates: a release lands when its work is done.

This file is the one place the roadmap is written. `:web:assembleSite` draws the "What comes next"
table on cartogenesis.com from it at build time, and `SiteAssemblyTest` fails if the page and this
table disagree, so there is nothing to keep in step by hand.

| Release | What it brings |
| --- | --- |
| 3.0.0 | The realism audit: the plates, the seas, the air and the water measured against Earth and corrected where they were wrong. |
| 3.1.0 (current) | Monsoon coasts, rain that reaches the interiors, vegetation that follows the rain, and ice sheets with the shape and weight of ice. |
| 3.2 | Erosion that reads the climate, and river channels that start where they should. |
| 3.3 | Waves and drift along the coasts. |
| 3.x | A topographic map style with contour lines, hydraulic erosion on the graphics card, and a choice of planet size. |
| 4.0 | The world as a sphere: physics in metres, a globe to turn, and a choice of map projections for the whole-world map and for exports. |
| 4.x | Graphics acceleration on a native WebGPU runtime, so the desktop runs the same kernels as the browser on Vulkan, Direct3D 12 or Metal. |
| 4.x | Import a heightmap, from Wonderdraft or any greyscale image, as the terrain a world is generated from. |
| 5.0 | The full atlas: named continents, seas, bays, straits and ranges, with labels set out the way a printed map sets them. |
| 6.0 | Drafting a world by hand: paint, raise and lower land as you watch, choose the kind of world the generator starts from, and place symbols from packs on layers, with everything drawn still put through the generator. |
