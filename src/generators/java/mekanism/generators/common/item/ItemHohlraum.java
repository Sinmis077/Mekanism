package mekanism.generators.common.item;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import mekanism.api.text.EnumColor;
import mekanism.common.MekanismGases;
import mekanism.common.item.ItemMekanism;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.text.TextComponentUtil;
import mekanism.common.util.text.Translation;
import mekanism.generators.common.MekanismGenerators;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class ItemHohlraum extends ItemMekanism implements IGasItem {

    public static final int MAX_GAS = 10;
    public static final int TRANSFER_RATE = 1;

    public ItemHohlraum() {
        super(MekanismGenerators.MODID, "hohlraum", new Item.Properties().maxStackSize(1));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        GasStack gasStack = getGas(stack);
        if (gasStack == null) {
            tooltip.add(TextComponentUtil.build(Translation.of("mekanism.tooltip.noGas"), "."));
            tooltip.add(TextComponentUtil.build(EnumColor.DARK_RED, Translation.of("tooltip.insufficientFuel")));
        } else {
            tooltip.add(TextComponentUtil.build(Translation.of("tooltip.stored"), " ", gasStack, ": " + gasStack.amount));
            if (gasStack.amount == getMaxGas(stack)) {
                tooltip.add(TextComponentUtil.build(EnumColor.DARK_GREEN, Translation.of("tooltip.readyForReaction"), "!"));
            } else {
                tooltip.add(TextComponentUtil.build(EnumColor.DARK_RED, Translation.of("tooltip.insufficientFuel")));
            }
        }
    }

    @Override
    public int getMaxGas(ItemStack itemstack) {
        return MAX_GAS;
    }

    @Override
    public int getRate(ItemStack itemstack) {
        return TRANSFER_RATE;
    }

    @Override
    public int addGas(ItemStack itemstack, GasStack stack) {
        if (getGas(itemstack) != null && getGas(itemstack).getGas() != stack.getGas()) {
            return 0;
        }
        //TODO: TAgs
        if (stack.getGas() != MekanismGases.FUSION_FUEL.getGas()) {
            return 0;
        }
        int toUse = Math.min(getMaxGas(itemstack) - getStored(itemstack), Math.min(getRate(itemstack), stack.amount));
        setGas(itemstack, new GasStack(stack.getGas(), getStored(itemstack) + toUse));
        return toUse;
    }

    @Override
    public GasStack removeGas(ItemStack itemstack, int amount) {
        return null;
    }

    public int getStored(ItemStack itemstack) {
        return getGas(itemstack) != null ? getGas(itemstack).amount : 0;
    }

    @Override
    public boolean canReceiveGas(ItemStack itemstack, Gas type) {
        //TODO: Tags
        return type == MekanismGases.FUSION_FUEL.getGas();
    }

    @Override
    public boolean canProvideGas(ItemStack itemstack, Gas type) {
        return false;
    }

    @Override
    public boolean showDurabilityBar(ItemStack stack) {
        return true;
    }

    @Override
    public double getDurabilityForDisplay(ItemStack stack) {
        return 1D - ((getGas(stack) != null ? (double) getGas(stack).amount : 0D) / (double) getMaxGas(stack));
    }

    @Override
    public int getRGBDurabilityForDisplay(@Nonnull ItemStack stack) {
        return MathHelper.hsvToRGB(Math.max(0.0F, (float) (1 - getDurabilityForDisplay(stack))) / 3.0F, 1.0F, 1.0F);
    }

    @Override
    public GasStack getGas(ItemStack itemstack) {
        return GasStack.readFromNBT(ItemDataUtils.getCompound(itemstack, "stored"));
    }

    @Override
    public void setGas(ItemStack itemstack, GasStack stack) {
        if (stack == null || stack.amount == 0) {
            ItemDataUtils.removeData(itemstack, "stored");
        } else {
            int amount = Math.max(0, Math.min(stack.amount, getMaxGas(itemstack)));
            GasStack gasStack = new GasStack(stack.getGas(), amount);
            ItemDataUtils.setCompound(itemstack, "stored", gasStack.write(new CompoundNBT()));
        }
    }

    @Override
    public void fillItemGroup(@Nonnull ItemGroup group, @Nonnull NonNullList<ItemStack> items) {
        super.fillItemGroup(group, items);
        if (!isInGroup(group)) {
            return;
        }
        ItemStack filled = new ItemStack(this);
        setGas(filled, new GasStack(MekanismGases.FUSION_FUEL, ((IGasItem) filled.getItem()).getMaxGas(filled)));
        items.add(filled);
    }
}