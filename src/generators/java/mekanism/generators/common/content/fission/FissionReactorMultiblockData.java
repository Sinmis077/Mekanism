package mekanism.generators.common.content.fission;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mekanism.api.Action;
import mekanism.api.Coord4D;
import mekanism.api.NBTConstants;
import mekanism.api.chemical.attribute.ChemicalAttributeValidator;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.api.chemical.gas.attribute.GasAttributes;
import mekanism.api.chemical.gas.attribute.GasAttributes.CooledCoolant;
import mekanism.api.chemical.gas.attribute.GasAttributes.HeatedCoolant;
import mekanism.api.chemical.gas.attribute.GasAttributes.Radiation;
import mekanism.api.inventory.AutomationType;
import mekanism.common.Mekanism;
import mekanism.common.capabilities.chemical.multiblock.MultiblockChemicalTankBuilder;
import mekanism.common.capabilities.fluid.MultiblockFluidTank;
import mekanism.common.capabilities.heat.MultiblockHeatCapacitor;
import mekanism.common.config.MekanismConfig;
import mekanism.common.integration.computer.ComputerException;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerChemicalTankWrapper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerHeatCapacitorWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.SyntheticComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.container.sync.dynamic.ContainerSync;
import mekanism.common.lib.multiblock.IValveHandler;
import mekanism.common.lib.multiblock.MultiblockData;
import mekanism.common.registries.MekanismGases;
import mekanism.common.util.HeatUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.NBTUtils;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.content.fission.FissionReactorValidator.FormedAssembly;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorCasing;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants.NBT;

public class FissionReactorMultiblockData extends MultiblockData implements IValveHandler {

    private static final double INVERSE_INSULATION_COEFFICIENT = 10_000;
    private static final double INVERSE_CONDUCTION_COEFFICIENT = 10;

    private static final double waterConductivity = 0.5;

    private static final int COOLANT_PER_VOLUME = 100_000;
    private static final long HEATED_COOLANT_PER_VOLUME = 1_000_000;
    private static final long FUEL_PER_ASSEMBLY = 8_000;

    public static final double MIN_DAMAGE_TEMPERATURE = 1_200;
    public static final double MAX_DAMAGE_TEMPERATURE = 1_800;
    public static final double MAX_DAMAGE = 100;

    private static final double EXPLOSION_CHANCE = 1D / (512_000);

    public final Set<FormedAssembly> assemblies = new LinkedHashSet<>();
    @ContainerSync
    @SyntheticComputerMethod(getter = "getFuelAssemblies")
    public int fuelAssemblies = 1;
    @ContainerSync
    @SyntheticComputerMethod(getter = "getFuelSurfaceArea")
    public int surfaceArea;

    @ContainerSync
    public IGasTank gasCoolantTank;
    @ContainerSync
    public MultiblockFluidTank<FissionReactorMultiblockData> fluidCoolantTank;
    @ContainerSync
    @WrappingComputerMethod(wrapper = ComputerChemicalTankWrapper.class, methodNames = {"getFuel", "getFuelCapacity", "getFuelNeeded"})
    public IGasTank fuelTank;

    @ContainerSync
    @WrappingComputerMethod(wrapper = ComputerChemicalTankWrapper.class, methodNames = {"getHeatedCoolant", "getHeatedCoolantCapacity", "getHeatedCoolantNeeded"})
    public IGasTank heatedCoolantTank;
    @ContainerSync
    @WrappingComputerMethod(wrapper = ComputerChemicalTankWrapper.class, methodNames = {"getWaste", "getWasteCapacity", "getWasteNeeded"})
    public IGasTank wasteTank;
    @ContainerSync
    @WrappingComputerMethod(wrapper = ComputerHeatCapacitorWrapper.class, methodNames = "getTemperature")
    public MultiblockHeatCapacitor<FissionReactorMultiblockData> heatCapacitor;

    @ContainerSync
    @SyntheticComputerMethod(getter = "getEnvironmentalLoss")
    public double lastEnvironmentLoss = 0;
    @ContainerSync
    @SyntheticComputerMethod(getter = "getHeatingRate")
    public long lastBoilRate = 0;
    @ContainerSync
    @SyntheticComputerMethod(getter = "getActualBurnRate")
    public double lastBurnRate = 0;
    public boolean clientBurning;
    @ContainerSync
    public double reactorDamage = 0;
    @ContainerSync
    @SyntheticComputerMethod(getter = "getBurnRate")
    public double rateLimit = MekanismGeneratorsConfig.generators.defaultBurnRate.get();
    public double burnRemaining = 0, partialWaste = 0;
    @ContainerSync
    protected boolean active;

    private AxisAlignedBB hotZone;

    public float prevCoolantScale;
    private float prevFuelScale;
    public float prevHeatedCoolantScale;
    private float prevWasteScale;

    public FissionReactorMultiblockData(TileEntityFissionReactorCasing tile) {
        super(tile);
        fluidCoolantTank = MultiblockFluidTank.create(this, tile, () -> getVolume() * COOLANT_PER_VOLUME,
              (stack, automationType) -> automationType != AutomationType.EXTERNAL, (stack, automationType) -> isFormed(),
              fluid -> fluid.getFluid().isIn(FluidTags.WATER) && gasCoolantTank.isEmpty(), null);
        fluidTanks.add(fluidCoolantTank);
        gasCoolantTank = MultiblockChemicalTankBuilder.GAS.create(this, tile, () -> (long) getVolume() * COOLANT_PER_VOLUME,
              (stack, automationType) -> automationType != AutomationType.EXTERNAL, (stack, automationType) -> isFormed(),
              gas -> gas.has(CooledCoolant.class) && fluidCoolantTank.isEmpty());
        fuelTank = MultiblockChemicalTankBuilder.GAS.create(this, tile, () -> fuelAssemblies * FUEL_PER_ASSEMBLY,
              (stack, automationType) -> automationType != AutomationType.EXTERNAL, (stack, automationType) -> isFormed(),
              gas -> gas == MekanismGases.FISSILE_FUEL.getChemical(), ChemicalAttributeValidator.ALWAYS_ALLOW, null);
        heatedCoolantTank = MultiblockChemicalTankBuilder.GAS.create(this, tile, () -> getVolume() * HEATED_COOLANT_PER_VOLUME,
              (stack, automationType) -> isFormed(), (stack, automationType) -> automationType != AutomationType.EXTERNAL,
              gas -> gas == MekanismGases.STEAM.get() || gas.has(HeatedCoolant.class));
        wasteTank = MultiblockChemicalTankBuilder.GAS.create(this, tile, () -> fuelAssemblies * FUEL_PER_ASSEMBLY,
              (stack, automationType) -> isFormed(), (stack, automationType) -> automationType != AutomationType.EXTERNAL,
              gas -> gas == MekanismGases.NUCLEAR_WASTE.getChemical(), ChemicalAttributeValidator.ALWAYS_ALLOW, null);
        gasTanks.addAll(Arrays.asList(fuelTank, heatedCoolantTank, wasteTank, gasCoolantTank));
        heatCapacitor = MultiblockHeatCapacitor.create(this, tile,
              MekanismGeneratorsConfig.generators.fissionCasingHeatCapacity.get(),
              () -> INVERSE_CONDUCTION_COEFFICIENT,
              () -> INVERSE_INSULATION_COEFFICIENT);
        heatCapacitors.add(heatCapacitor);
    }

    @Override
    public void onCreated(World world) {
        super.onCreated(world);
        // update the heat capacity now that we've read
        heatCapacitor.setHeatCapacity(MekanismGeneratorsConfig.generators.fissionCasingHeatCapacity.get() * locations.size(), true);
        hotZone = new AxisAlignedBB(getMinPos().getX() + 1, getMinPos().getY() + 1, getMinPos().getZ() + 1,
              getMaxPos().getX(), getMaxPos().getY(), getMaxPos().getZ());
    }

    @Override
    public boolean tick(World world) {
        boolean needsPacket = super.tick(world);
        // burn reactor fuel, create energy
        if (isActive()) {
            burnFuel(world);
        } else {
            lastBurnRate = 0;
        }
        if (isBurning() != clientBurning) {
            needsPacket = true;
            clientBurning = isBurning();
        }
        // handle coolant heating (water -> steam)
        handleCoolant();
        // external heat dissipation
        lastEnvironmentLoss = simulateEnvironment();
        // update temperature
        updateHeatCapacitors(null);
        handleDamage(world);
        radiateEntities(world);

        // update scales
        float coolantScale = MekanismUtils.getScale(prevCoolantScale, fluidCoolantTank);
        float fuelScale = MekanismUtils.getScale(prevFuelScale, fuelTank);
        float steamScale = MekanismUtils.getScale(prevHeatedCoolantScale, heatedCoolantTank), wasteScale = MekanismUtils.getScale(prevWasteScale, wasteTank);
        if (coolantScale != prevCoolantScale || fuelScale != prevFuelScale || steamScale != prevHeatedCoolantScale || wasteScale != prevWasteScale) {
            needsPacket = true;
            prevCoolantScale = coolantScale;
            prevFuelScale = fuelScale;
            prevHeatedCoolantScale = steamScale;
            prevWasteScale = wasteScale;
        }
        return needsPacket;
    }

    @Override
    public void readUpdateTag(CompoundNBT tag) {
        super.readUpdateTag(tag);
        NBTUtils.setFloatIfPresent(tag, NBTConstants.SCALE, scale -> prevCoolantScale = scale);
        NBTUtils.setFloatIfPresent(tag, NBTConstants.SCALE_ALT, scale -> prevFuelScale = scale);
        NBTUtils.setFloatIfPresent(tag, NBTConstants.SCALE_ALT_2, scale -> prevHeatedCoolantScale = scale);
        NBTUtils.setFloatIfPresent(tag, NBTConstants.SCALE_ALT_3, scale -> prevWasteScale = scale);
        NBTUtils.setIntIfPresent(tag, NBTConstants.VOLUME, this::setVolume);
        NBTUtils.setFluidStackIfPresent(tag, NBTConstants.FLUID_STORED, value -> fluidCoolantTank.setStack(value));
        NBTUtils.setGasStackIfPresent(tag, NBTConstants.GAS_STORED, value -> fuelTank.setStack(value));
        NBTUtils.setGasStackIfPresent(tag, NBTConstants.GAS_STORED_ALT, value -> heatedCoolantTank.setStack(value));
        NBTUtils.setGasStackIfPresent(tag, NBTConstants.GAS_STORED_ALT_2, value -> wasteTank.setStack(value));
        readValves(tag);
        assemblies.clear();
        if (tag.contains(NBTConstants.ASSEMBLIES, NBT.TAG_LIST)) {
            ListNBT list = tag.getList(NBTConstants.ASSEMBLIES, NBT.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                assemblies.add(FormedAssembly.read(list.getCompound(i)));
            }
        }
    }

    @Override
    public void writeUpdateTag(CompoundNBT tag) {
        super.writeUpdateTag(tag);
        tag.putFloat(NBTConstants.SCALE, prevCoolantScale);
        tag.putFloat(NBTConstants.SCALE_ALT, prevFuelScale);
        tag.putFloat(NBTConstants.SCALE_ALT_2, prevHeatedCoolantScale);
        tag.putFloat(NBTConstants.SCALE_ALT_3, prevWasteScale);
        tag.putInt(NBTConstants.VOLUME, getVolume());
        tag.put(NBTConstants.FLUID_STORED, fluidCoolantTank.getFluid().writeToNBT(new CompoundNBT()));
        tag.put(NBTConstants.GAS_STORED, fuelTank.getStack().write(new CompoundNBT()));
        tag.put(NBTConstants.GAS_STORED_ALT, heatedCoolantTank.getStack().write(new CompoundNBT()));
        tag.put(NBTConstants.GAS_STORED_ALT_2, wasteTank.getStack().write(new CompoundNBT()));
        writeValves(tag);
        ListNBT list = new ListNBT();
        assemblies.forEach(assembly -> list.add(assembly.write()));
        tag.put(NBTConstants.ASSEMBLIES, list);
    }

    private void handleDamage(World world) {
        double temp = heatCapacitor.getTemperature();
        if (temp > MIN_DAMAGE_TEMPERATURE) {
            double damageRate = Math.min(temp, MAX_DAMAGE_TEMPERATURE) / (MIN_DAMAGE_TEMPERATURE * 10);
            reactorDamage += damageRate;
        } else {
            double repairRate = (MIN_DAMAGE_TEMPERATURE - temp) / (MIN_DAMAGE_TEMPERATURE * 100);
            reactorDamage = Math.max(0, reactorDamage - repairRate);
        }
        // consider a meltdown only if it's config-enabled, we're passed the damage threshold and the temperature is still dangerous
        if (MekanismGeneratorsConfig.generators.fissionMeltdownsEnabled.get() && reactorDamage >= MAX_DAMAGE && temp >= MIN_DAMAGE_TEMPERATURE) {
            if (world.rand.nextDouble() < (reactorDamage / MAX_DAMAGE) * MekanismGeneratorsConfig.generators.fissionMeltdownChance.get()) {
                double radiation = wasteTank.getStored() * MekanismGases.NUCLEAR_WASTE.get().get(GasAttributes.Radiation.class).getRadioactivity();
                if (wasteTank.getStack().has(GasAttributes.Radiation.class)) {
                    radiation += wasteTank.getStored() * wasteTank.getStack().get(GasAttributes.Radiation.class).getRadioactivity();
                }
                radiation *= MekanismGeneratorsConfig.generators.fissionMeltdownRadiationMultiplier.get();
                Mekanism.radiationManager.radiate(new Coord4D(getBounds().getCenter(), world), radiation);
                Mekanism.radiationManager.createMeltdown(world, getMinPos(), getMaxPos(), heatCapacitor.getHeat(), EXPLOSION_CHANCE);
            }
        }
    }

    private void handleCoolant() {
        double temp = heatCapacitor.getTemperature();
        double heat = getBoilEfficiency() * (temp - HeatUtils.BASE_BOIL_TEMP) * heatCapacitor.getHeatCapacity();
        long coolantHeated = 0;

        if (!fluidCoolantTank.isEmpty()) {
            double caseCoolantHeat = heat * waterConductivity;
            coolantHeated = (int) (HeatUtils.getSteamEnergyEfficiency() * caseCoolantHeat / HeatUtils.getWaterThermalEnthalpy());
            coolantHeated = Math.max(0, Math.min(coolantHeated, fluidCoolantTank.getFluidAmount()));
            if (coolantHeated > 0) {
                MekanismUtils.logMismatchedStackSize(fluidCoolantTank.shrinkStack((int) coolantHeated, Action.EXECUTE), coolantHeated);
                // extra steam is dumped
                heatedCoolantTank.insert(MekanismGases.STEAM.getStack(coolantHeated), Action.EXECUTE, AutomationType.INTERNAL);
                caseCoolantHeat = coolantHeated * HeatUtils.getWaterThermalEnthalpy() / HeatUtils.getSteamEnergyEfficiency();
                heatCapacitor.handleHeat(-caseCoolantHeat);
            }
        } else if (!gasCoolantTank.isEmpty()) {
            CooledCoolant coolantType = gasCoolantTank.getStack().get(CooledCoolant.class);
            if (coolantType != null) {
                double caseCoolantHeat = heat * coolantType.getConductivity();
                coolantHeated = (int) (caseCoolantHeat / coolantType.getThermalEnthalpy());
                coolantHeated = Math.max(0, Math.min(coolantHeated, gasCoolantTank.getStored()));
                if (coolantHeated > 0) {
                    MekanismUtils.logMismatchedStackSize(gasCoolantTank.shrinkStack((int) coolantHeated, Action.EXECUTE), coolantHeated);
                    heatedCoolantTank.insert(coolantType.getHeatedGas().getStack(coolantHeated), Action.EXECUTE, AutomationType.INTERNAL);
                    caseCoolantHeat = coolantHeated * coolantType.getThermalEnthalpy();
                    heatCapacitor.handleHeat(-caseCoolantHeat);
                }
            }
        }
        lastBoilRate = coolantHeated;
    }

    private void burnFuel(World world) {
        double storedFuel = fuelTank.getStored() + burnRemaining;
        double toBurn = Math.min(Math.min(rateLimit, storedFuel), fuelAssemblies * MekanismGeneratorsConfig.generators.burnPerAssembly.get());
        storedFuel -= toBurn;
        fuelTank.setStackSize((long) storedFuel, Action.EXECUTE);
        burnRemaining = storedFuel % 1;
        heatCapacitor.handleHeat(toBurn * MekanismGeneratorsConfig.generators.energyPerFissionFuel.get().doubleValue());
        // handle waste
        partialWaste += toBurn;
        long newWaste = (long) Math.floor(partialWaste);
        if (newWaste > 0) {
            partialWaste %= 1;
            long leftoverWaste = Math.max(0, newWaste - wasteTank.getNeeded());
            GasStack wasteToAdd = MekanismGases.NUCLEAR_WASTE.getStack(newWaste);
            wasteTank.insert(wasteToAdd, Action.EXECUTE, AutomationType.INTERNAL);
            if (leftoverWaste > 0) {
                double radioactivity = wasteToAdd.getType().get(GasAttributes.Radiation.class).getRadioactivity();
                Mekanism.radiationManager.radiate(new Coord4D(getBounds().getCenter(), world), leftoverWaste * radioactivity);
            }
        }
        // update previous burn
        lastBurnRate = toBurn;
    }

    private void radiateEntities(World world) {
        if (MekanismConfig.general.radiationEnabled.get() && isBurning() && world.getRandom().nextInt() % 20 == 0) {
            List<LivingEntity> entitiesToRadiate = getWorld().getEntitiesWithinAABB(LivingEntity.class, hotZone);
            for (LivingEntity entity : entitiesToRadiate) {
                double wasteRadiation = 0;
                if (wasteTank.getStored() > 0) {
                    Radiation r = wasteTank.getType().get(Radiation.class);
                    if (r != null) {
                        wasteRadiation = r.getRadioactivity() * wasteTank.getStored() / 3_600F; // divide down to Sv/s
                    }
                }
                Mekanism.radiationManager.radiate(entity, lastBurnRate + wasteRadiation);
            }
        }
    }

    @ComputerMethod(nameOverride = "getStatus")
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isBurning() {
        return lastBurnRate > 0;
    }

    public boolean handlesSound(TileEntityFissionReactorCasing tile) {
        return getBounds().isOnCorner(tile.getPos());
    }

    @ComputerMethod
    public double getBoilEfficiency() {
        double avgSurfaceArea = (double) surfaceArea / (double) fuelAssemblies;
        return Math.min(1, avgSurfaceArea / MekanismGeneratorsConfig.generators.fissionSurfaceAreaTarget.get());
    }

    @ComputerMethod
    public long getMaxBurnRate() {
        return fuelAssemblies * MekanismGeneratorsConfig.generators.burnPerAssembly.get();
    }

    @ComputerMethod
    public long getDamagePercent() {
        return Math.round(reactorDamage / FissionReactorMultiblockData.MAX_DAMAGE) * 100;
    }

    @Override
    protected int getMultiblockRedstoneLevel() {
        return MekanismUtils.redstoneLevelFromContents(fuelTank.getStored(), fuelTank.getCapacity());
    }

    //Computer related methods
    @ComputerMethod
    private void activate() throws ComputerException {
        if (isActive()) {
            throw new ComputerException("Reactor is already active.");
        }
        setActive(true);
    }

    @ComputerMethod
    private void scram() throws ComputerException {
        if (!isActive()) {
            throw new ComputerException("Scram requires the reactor to be active.");
        }
        setActive(false);
    }

    @ComputerMethod
    private void setBurnRate(double rate) throws ComputerException {
        //Round to two decimal places
        rate = (double) Math.round(rate * 100) / 100;
        long max = getMaxBurnRate();
        if (rate < 0 || rate > max) {
            //Validate bounds even though we can clamp
            throw new ComputerException("Burn Rate '%d' is out of range must be between 0 and %d. (Inclusive)", rate, max);
        }
        rateLimit = Math.max(Math.min(getMaxBurnRate(), rate), 0);
    }

    @ComputerMethod
    private Object getCoolant() {
        if (fluidCoolantTank.isEmpty() && !gasCoolantTank.isEmpty()) {
            return gasCoolantTank.getStack();
        }
        return fluidCoolantTank.getFluid();
    }

    @ComputerMethod
    private long getCoolantCapacity() {
        if (fluidCoolantTank.isEmpty() && !gasCoolantTank.isEmpty()) {
            return gasCoolantTank.getCapacity();
        }
        return fluidCoolantTank.getCapacity();
    }

    @ComputerMethod
    private long getCoolantNeeded() {
        if (fluidCoolantTank.isEmpty() && !gasCoolantTank.isEmpty()) {
            return gasCoolantTank.getNeeded();
        }
        return fluidCoolantTank.getNeeded();
    }

    @ComputerMethod
    private double getHeatCapacity() {
        return heatCapacitor.getHeatCapacity();
    }
    //End computer related methods
}