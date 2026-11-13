# Provision item artwork

Replaces the common `untextured_item` artwork of the existing 40
`ModProvisionItems` entries. Registry identities/order, categories and behavior
are unchanged. No nutrition, consumption, recipes or harvest rules are introduced.

Five original imagegen diffuse atlases contain eight original labels each.
The production PNGs are uniform 50% reductions of the preserved originals.
There is no procedural noise substitute and no retouching. The complete prompts
and source/production hashes are in this directory.

`tools/provision_art.py` authors the 40 ordinary item meshes and source wrappers.
The standard baked item pipeline loads them through `ProvisionItemModels`;
there is no per-frame custom renderer or item entity simulation.
Every source wrapper declares its texture dependencies and standard display
transforms. Existing accepted furniture materials supply unprinted paper,
metal and opaque stylized glass; their PNGs are never modified.

Labels use a guarded 4×2 tile layout. Curved containers have continuous paper
wraps and correctly oriented front/back prints. Pouches have one owned printed
front surface to avoid coincident faces and flickering. All models stay inside
0..16 model units and below 600 faces each.

Static contract: `tools/check-provision-art.py`.
Native fixture: `runProvisionArtClientTest`, isolated disposable world only.
Review generation: `tools/provision_art_review.py`.
The output page distinguishes original artwork from untouched native client PNGs.
The technical result is verified separately from user artistic acceptance.
