package mekanism.client.render.obj;

import com.google.common.collect.ImmutableMap;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.PerspectiveMapWrapper;
import net.minecraftforge.client.model.obj.OBJModel;
import net.minecraftforge.common.model.IModelState;
import net.minecraftforge.common.model.TRSRTransformation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * OBJModel which (on bake) wraps the baked OBJ in {@link PerspectiveMapWrapper} with the transforms we got from the vanilla model.
 *
 * @author Thiakil
 */
public class MekanismOBJModelWithTransforms extends OBJModel
{
	public ResourceLocation location;
	private ItemCameraTransforms transforms;

	public MekanismOBJModelWithTransforms(MaterialLibrary matLib, ResourceLocation modelLocation, ItemCameraTransforms transforms)
	{
		super(matLib, modelLocation);
		
		this.transforms = transforms;
		location = modelLocation;
	}
	
	@Override
	public IBakedModel bake(IModelState state, VertexFormat format, Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
	{
		IBakedModel preBaked = super.bake(state, format, bakedTextureGetter);
		return new PerspectiveMapWrapper(preBaked, PerspectiveMapWrapper.getTransforms(transforms));
	}
	
	@Override
    public IModel process(ImmutableMap<String, String> customData)
    {
    	MekanismOBJModelWithTransforms ret = new MekanismOBJModelWithTransforms(getMatLib(), location, transforms);
        return ret;
    }

    @Override
    public IModel retexture(ImmutableMap<String, String> textures)
    {
    	MekanismOBJModelWithTransforms ret = new MekanismOBJModelWithTransforms(getMatLib().makeLibWithReplacements(textures), location, transforms);
        return ret;
    }
}