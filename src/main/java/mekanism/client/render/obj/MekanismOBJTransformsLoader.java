package mekanism.client.render.obj;

import mekanism.common.Mekanism;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ModelBlock;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.obj.OBJLoader;
import net.minecraftforge.client.model.obj.OBJModel;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom OBJ loader that loads a minecraft json from the standard location, for the camera transforms,
 * and loads them into a normal OBJ model (located in models/obj/) from Forge's loader.
 *
 * Items MUST be manually registered to the loader with {@link MekanismOBJTransformsLoader#INSTANCE#registerOBJWithTransforms(ResourceLocation)}.
 *
 * @author Thiakil
 */
@SideOnly(Side.CLIENT)
public class MekanismOBJTransformsLoader implements ICustomModelLoader
{
	public static MekanismOBJTransformsLoader INSTANCE = new MekanismOBJTransformsLoader();

	private IResourceManager resourceManager;

	private List<ResourceLocation> knownOBJJsons = new ArrayList<>();

	private Class vanillaModelWrapper;
	private Field vanillaModelField;
	private Method vanillaModelGetTextures;
	private ICustomModelLoader vanillaLoader;

	private MekanismOBJTransformsLoader(){
		try
		{
			vanillaModelWrapper = Class.forName("net.minecraftforge.client.model.ModelLoader$VanillaModelWrapper");
			vanillaModelField = ReflectionHelper.findField(vanillaModelWrapper, "model");
			vanillaModelGetTextures = ReflectionHelper.findMethod(vanillaModelWrapper, "getTextures", "getTextures");
			vanillaLoader = (ICustomModelLoader)ReflectionHelper.findField(Class.forName("net.minecraftforge.client.model.ModelLoader$VanillaLoader"), "INSTANCE").get(null);
		} catch (ClassNotFoundException e){
			Mekanism.logger.error("[MekanismOBJTransformsLoader] Did not find VanillaModelWrapper", e);
		} catch (Exception e){
			Mekanism.logger.error("[MekanismOBJTransformsLoader] Didn't find method/field", e);
		}
	}

	public void registerOBJWithTransforms(ResourceLocation loc){
		knownOBJJsons.add(loc);
	}

	@Override
	public boolean accepts(@Nonnull ResourceLocation modelLocation)
	{
		ResourceLocation baseLoc = new ResourceLocation(modelLocation.getResourceDomain(), modelLocation.getResourcePath().replaceAll("models/(item|block)/", ""));
		return knownOBJJsons.contains(baseLoc);
	}

	@Override
	public IModel loadModel(@Nonnull ResourceLocation modelLocation) throws Exception
	{
		OBJModel objModel = (OBJModel)OBJLoader.INSTANCE.loadModel(getOBJLocation(modelLocation));
		IModel transformsModel = vanillaLoader.loadModel(modelLocation);
		ItemCameraTransforms transforms = ItemCameraTransforms.DEFAULT;
		if (vanillaModelWrapper.isInstance(transformsModel)){
			vanillaModelGetTextures.invoke(transformsModel);//force it to load parents
			ModelBlock baseModel = (ModelBlock)vanillaModelField.get(transformsModel);
			transforms = baseModel.getAllTransforms();
		}
		return new MekanismOBJModelWithTransforms(objModel.getMatLib(), modelLocation,transforms);
	}

	private static ResourceLocation getOBJLocation(ResourceLocation loc){
		return new ResourceLocation(loc.getResourceDomain(), loc.getResourcePath().replaceFirst("models/(item|block)/", "models/obj/")+".obj");
	}

	@Override
	public void onResourceManagerReload(@Nonnull IResourceManager resourceManager)
	{
		this.resourceManager = resourceManager;
	}
}