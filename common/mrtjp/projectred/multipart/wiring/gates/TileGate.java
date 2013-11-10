package mrtjp.projectred.multipart.wiring.gates;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import mrtjp.projectred.ProjectRed;
import mrtjp.projectred.interfaces.wiring.IBundledEmitter;
import mrtjp.projectred.interfaces.wiring.IBundledUpdatable;
import mrtjp.projectred.interfaces.wiring.IBundledWire;
import mrtjp.projectred.interfaces.wiring.IConnectable;
import mrtjp.projectred.interfaces.wiring.IRedstoneUpdatable;
import mrtjp.projectred.interfaces.wiring.IRedstoneWire;
import mrtjp.projectred.interfaces.wiring.IWire;
import mrtjp.projectred.multipart.TileCoverableBase;
import mrtjp.projectred.multipart.microblocks.EnumPosition;
import mrtjp.projectred.multipart.microblocks.Part;
import mrtjp.projectred.multipart.microblocks.PartType;
import mrtjp.projectred.multipart.wiring.gates.GateLogic.WorldStateBound;
import mrtjp.projectred.utils.BasicUtils;
import mrtjp.projectred.utils.BasicWireUtils;
import mrtjp.projectred.utils.Coords;
import mrtjp.projectred.utils.Dir;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.ForgeDirection;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class TileGate extends TileCoverableBase implements IRedstoneUpdatable, IConnectable, IBundledUpdatable, IBundledEmitter {
	private EnumGate type;

	/** Server-side logic for gate **/
	private GateLogic logic;

	/**
	 * The inside face for the containing block that its sitting on. (0 for
	 * sitting on top of a block.)
	 **/
	private byte side;

	/** The ForgeDirection that the front of the gate is facing. **/
	private byte front;

	private boolean isNotStateless;
	private int gateSettings;
	private GateLogic.WithPointer pointer;
	private boolean flipped;
	private boolean hasBundledConnections;

	float pointerPos; // rendering only
	float pointerSpeed; // rendering only

	@Override
	public Packet getDescriptionPacket() {
		if (type == null) {
			return null;
		}

		Packet132TileEntityData p = new Packet132TileEntityData(xCoord, yCoord, zCoord, 0, new NBTTagCompound());
		p.data.setByteArray("c", getCoverSystem().writeDescriptionBytes());
		p.data.setByte("t", (byte) (type.ordinal() | (flipped ? 0x80 : 0)));
		p.data.setByte("s", side);
		p.data.setByte("f", front);
		p.data.setShort("r", (short) prevRenderState);
		if (pointer != null) {
			p.data.setShort("p", (short) pointer.getPointerPosition());
			p.data.setFloat("P", pointer.getPointerSpeed());
		}
		return p;
	}

	@Override
	public void onDataPacket(INetworkManager net, Packet132TileEntityData pkt) {
		getCoverSystem().readDescriptionBytes(pkt.data.getByteArray("c"), 0);
		type = EnumGate.VALUES[pkt.data.getByte("t") & 0x7F];
		side = pkt.data.getByte("s");
		front = pkt.data.getByte("f");
		flipped = (pkt.data.getByte("t") & 0x80) != 0;

		prevRenderState = pkt.data.getShort("r") & 0xFFFFF;

		if (pkt.data.hasKey("p")) {
			pointerPos = pkt.data.getShort("p");
			pointerSpeed = pkt.data.getFloat("P");
		}

		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}

	public int getSide() {
		return side;
	}

	public TileGate(EnumGate type, int side, int front) {
		if (type == null) {
			throw new IllegalArgumentException("type cannot be null");
		}
		this.type = type;
		this.side = (byte) side;
		this.front = (byte) front;
		createLogic();
	}

	public TileGate() {
	}

	private long toBitfield8(short[] a) {
		if (a.length > 8)
			throw new IllegalArgumentException("array too long");
		long rv = 0;
		for (int k = 0; k < a.length; k++) {
			if (a[k] < 0 || a[k] > 255) {
				throw new IllegalArgumentException("element out of range (index " + k + ", value " + a[k] + ")");
			}
			rv = (rv << 8) | a[k];
		}
		return rv;
	}

	private long toBitfield16(short[] a) {
		if (a.length > 4) {
			throw new IllegalArgumentException("array too long");
		}
		long rv = 0;
		for (int k = 0; k < a.length; k++)
			rv = (rv << 16) | a[k];
		return rv;
	}

	private void fromBitfield8(long bf, short[] a) {
		if (a.length > 8) {
			throw new IllegalArgumentException("array too long");
		}
		for (int k = a.length - 1; k >= 0; k--) {
			a[k] = (short) (bf & 255);
			bf >>= 8;
		}
	}

	private void fromBitfield16(long bf, short[] a) {
		if (a.length > 4) {
			throw new IllegalArgumentException("array too long");
		}
		for (int k = a.length - 1; k >= 0; k--) {
			a[k] = (short) bf;
			bf >>= 16;
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);

		tag.setByte("type", type == null ? -1 : (byte) type.ordinal());
		tag.setByte("side", side);
		tag.setByte("front", front);

		tag.setBoolean("version2", true);

		tag.setLong("outputs", toBitfield16(outputs));
		tag.setLong("inputs", toBitfield16(inputs));
		tag.setLong("prevOutputs", toBitfield16(prevOutputs));

		tag.setShort("renderState", (short) renderState);
		tag.setShort("prevRenderState", (short) prevRenderState);
		tag.setBoolean("updatePending", updatePending);
		tag.setShort("gateSettings", (short) gateSettings);
		tag.setBoolean("flipped", flipped);
		if (logic != null && isNotStateless) {
			NBTTagCompound tag2 = new NBTTagCompound();
			logic.write(tag2);
			tag.setTag("logic", tag2);
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		try {
			type = EnumGate.VALUES[tag.getByte("type")];
		} catch (Exception e) {
			type = EnumGate.AND; // shouldn't happen
		}
		side = tag.getByte("side");
		front = tag.getByte("front");
		flipped = tag.getBoolean("flipped");

		renderState = tag.getShort("renderState") & 0xFFFF;
		prevRenderState = tag.getShort("prevRenderState") & 0xFFFF;

		updatePending = tag.getBoolean("updatePending");
		gateSettings = tag.getShort("gateSettings") & 0xFFFF;

		if (tag.getTag("inputs") instanceof NBTTagLong) {
			if (tag.getBoolean("version2")) {
				fromBitfield16(tag.getLong("inputs"), inputs);
				fromBitfield16(tag.getLong("outputs"), outputs);
				fromBitfield16(tag.getLong("prevOutputs"), prevOutputs);

				for (int k = 0; k < 4; k++) {
					absOutputs[relToAbsDirection(k)] = outputs[k];
					prevAbsOutputs[relToAbsDirection(k)] = prevOutputs[k];
				}

			} else {
				fromBitfield8(tag.getLong("inputs"), inputs);
				fromBitfield8(tag.getLong("outputs"), outputs);
				fromBitfield8(tag.getLong("absOutputs"), absOutputs);
				fromBitfield8(tag.getLong("prevAbsOutputs"), prevAbsOutputs);
				fromBitfield8(tag.getLong("prevOutputs"), prevOutputs);
			}
		}

		createLogic();

		if (logic != null && tag.hasKey("logic")) {
			logic.read(tag.getCompoundTag("logic"));
		}
	}

	private void createLogic() {
		logic = type.createLogic();
		isNotStateless = !(logic instanceof GateLogic.Stateless);
		if (logic instanceof GateLogic.WithPointer) {
			pointer = (GateLogic.WithPointer) logic;
		} else {
			pointer = null;
		}
		hasBundledConnections = logic instanceof GateLogic.WithBundledConnections;
	}

	private boolean isFirstTick = true;

	@Override
	public void updateEntity() {
		super.updateEntity();
		if (BasicUtils.isServer(worldObj)) {
			if (isNotStateless) {
				updateLogic(true, false);
				if (logic instanceof WorldStateBound) {
					if (((WorldStateBound)logic).needsWorldInfo()) {
						((WorldStateBound)logic).setWorldInfo(worldObj, xCoord, yCoord, zCoord);
					}
				}
			} else if (isFirstTick) {
				updateLogic(false, false);
				isFirstTick = false;
			}

		} else {
			pointerPos += pointerSpeed;
		}
	}

	public int getFront() {
		return front;
	}

	public EnumGate getType() {
		return type;
	}

	private short[] inputs = new short[4];
	private short[] outputs = new short[4];
	private short[] absOutputs = new short[6];
	private short[] prevAbsOutputs = new short[6];
	private short[] prevOutputs = new short[4];
	private int renderState;
	private int prevRenderState;
	private boolean updatePending;

	private void updateRenderState() {
		renderState = logic.getRenderState(inputs, prevOutputs, gateSettings);
		if (prevRenderState != renderState) {
			prevRenderState = renderState;
			worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		}
	}

	private static int[] FLIPMAP_FLIPPED = new int[] { 0, 1, 3, 2 };
	private static int[] FLIPMAP_UNFLIPPED = new int[] { 0, 1, 2, 3 };

	private int relToAbsDirection(int rel) {
		if (flipped) {
			rel = FLIPMAP_FLIPPED[rel];
		}
		return BasicWireUtils.dirMap[side][front][rel];
	}

	private int absToRelDirection(int abs) {
		if ((abs & 6) == (side & 6))
			return -1;

		int rel = BasicWireUtils.invDirMap[side][front][abs];
		if (flipped)
			rel = FLIPMAP_FLIPPED[rel];
		return rel;
	}

	public void updateLogic(boolean fromTick, boolean forceUpdate) {
		if (type == null)
			return;

		int[] flipMap = flipped ? FLIPMAP_FLIPPED : FLIPMAP_UNFLIPPED;

		for (int k = 0; k < 4; k++) {
			inputs[flipMap[k]] = getInputValue(k);
		}
		// update render state with new inputs but not new outputs
		updateRenderState();

		if (forceUpdate || fromTick == isNotStateless) {
			logic.update(inputs, outputs, gateSettings);

			for (int k = 0; k < 4; k++) {
				absOutputs[BasicWireUtils.dirMap[side][front][k]] = outputs[flipMap[k]];
			}
			absOutputs[side] = absOutputs[side ^ 1] = 0;

			if (forceUpdate || !Arrays.equals(outputs, prevOutputs)) {
				if (!updatePending) {
					worldObj.scheduleBlockUpdate(xCoord, yCoord, zCoord, ProjectRed.blockGate.blockID, 2);
					updatePending = true;
				}
			}
		}
	}

	private short getInputValue(int rel) {
		int abs = relToAbsDirection(rel);
		if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel)) {
			return getBundledInputBitmask(abs);
		} else {
			return getInputStrength(abs);
		}
	}

	private short getBundledInputBitmask(int abs) {
		ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[abs];
		int x = xCoord + fd.offsetX, y = yCoord + fd.offsetY, z = zCoord + fd.offsetZ;
		TileEntity te = worldObj.getBlockTileEntity(x, y, z);

		if (te instanceof IBundledEmitter) {
			byte[] values = ((IBundledEmitter) te).getBundledCableStrength(side, abs ^ 1);
			if (values == null)
				return 0;

			short rv = 0;
			for (int k = 15; k >= 0; k--) {
				rv <<= 1;
				if (values[k] != 0)
					rv |= 1;
			}

			return rv;
		}
		return 0;
	}

	private short getInputStrength(int abs) {
		switch (abs) {
		case Dir.NX:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord - 1, yCoord, zCoord, abs ^ 1, side);
		case Dir.PX:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord + 1, yCoord, zCoord, abs ^ 1, side);
		case Dir.NY:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord, yCoord - 1, zCoord, abs ^ 1, side);
		case Dir.PY:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord, yCoord + 1, zCoord, abs ^ 1, side);
		case Dir.NZ:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord, yCoord, zCoord - 1, abs ^ 1, side);
		case Dir.PZ:
			return BasicWireUtils.getPowerStrength(worldObj, xCoord, yCoord, zCoord + 1, abs ^ 1, side);
		}
		throw new IllegalArgumentException("Invalid direction " + abs);
	}

	public int getVanillaOutputStrength(int dir) {
		int rel = absToRelDirection(dir);
		if (rel < 0)
			return 0;

		if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel))
			return 0;

		return prevAbsOutputs[dir] / 17;
	}

	public int getRenderState() {
		return prevRenderState;
	}

	public void scheduledTick() {
		updatePending = false;
		System.arraycopy(absOutputs, 0, prevAbsOutputs, 0, 6);
		System.arraycopy(outputs, 0, prevOutputs, 0, 4);
		updateRenderState();
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord - 1, zCoord, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord + 1, zCoord, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord - 1, yCoord, zCoord, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord + 1, yCoord, zCoord, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord - 1, ProjectRed.blockGate.blockID);
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord + 1, ProjectRed.blockGate.blockID);

		if (hasBundledConnections) {
			for (int rel = 0; rel < 4; rel++) {
				if (!((GateLogic.WithBundledConnections) logic).isBundledConnection(rel)) {
					continue;
				}

				int abs = relToAbsDirection(rel);
				ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[abs];
				int x = xCoord + fd.offsetX, y = yCoord + fd.offsetY, z = zCoord + fd.offsetZ;

				TileEntity te = worldObj.getBlockTileEntity(x, y, z);
				if (te != null && te instanceof IBundledUpdatable)
					((IBundledUpdatable) te).onBundledInputChanged();
			}
		}
	}

	// called when shift-clicked by a screwdriver
	public void configure() {
		if (logic instanceof GateLogic.Flippable) {
			// flipped = !flipped; Disabled, until i find a practical way to
			// render flipped.
			worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		} else {
			gateSettings = logic.configure(gateSettings);
		}
		updateLogic(false, true);
	}

	// Used to rotate to correct side based on where it is now.
	public byte[][] rotationMap = { { -1, -1, 5, 4, 2, 3 }, // Side 0
			{ -1, -1, 4, 5, 3, 2 }, // Side 1
			{ 4, 5, -1, -1, 1, 0 }, // Side 2
			{ 5, 4, -1, -1, 0, 1 }, // Side 3
			{ 3, 2, 0, 1, -1, -1 }, // Side 4
			{ 2, 3, 1, 0, -1, -1 } // Side 5
	};

	// called when non-shift-clicked by a screwdriver
	public void rotate() {
		if (rotationMap[side][front] == -1) {
			return;
		}
		front = rotationMap[side][front];
		updateLogic(false, true);
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}

	public boolean onBlockActivated(EntityPlayer ply) {
		if (ply.getHeldItem() != null && ply.getHeldItem().getItem() == ProjectRed.itemScrewdriver)
			return false;

		if (worldObj.isRemote) {
			return type != null && GateLogic.WithRightClickAction.class.isAssignableFrom(type.getLogicClass());
		}
		if (logic instanceof GateLogic.WithRightClickAction) {
			((GateLogic.WithRightClickAction) logic).onRightClick(ply, this);
			return true;
		}
		return false;
	}

	public GateLogic getLogic() {
		return logic;
	}

	public boolean isFlipped() {
		return flipped;
	}

	@Override
	public boolean isPlacementBlockedByTile(PartType<?> type, EnumPosition pos) {
		return !getPartAABBFromPool(0).intersectsWith(Part.getBoundingBoxFromPool(pos, type.getSize()));
	}

	@Override
	public boolean isPositionOccupiedByTile(EnumPosition pos) {
		return pos == EnumPosition.getFacePosition(side);
	}

	@Override
	public EnumPosition getPartPosition(int subHit) {
		if (subHit == 0)
			return EnumPosition.getFacePosition(side);
		return null;
	}

	@Override
	public AxisAlignedBB getPartAABBFromPool(int subHit) {
		if (subHit == 0)
			return Part.getBoundingBoxFromPool(EnumPosition.getFacePosition(side), BlockGate.THICKNESS);
		return null;
	}

	@Override
	protected int getNumTileOwnedParts() {
		return 1;
	}

	@Override
	public float getPlayerRelativePartHardness(EntityPlayer ply, int part) {
		return ply.getCurrentPlayerStrVsBlock(ProjectRed.blockGate, false, getBlockMetadata()) / 0.25f / 30f;
	}

	@Override
	public ItemStack pickPart(MovingObjectPosition rayTrace, int part) {
		return new ItemStack(ProjectRed.blockGate, 1, getType().ordinal());
	}

	@Override
	public boolean isSolidOnSide(ForgeDirection side) {
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void render(RenderBlocks render) {
		GateStaticRenderer.instance.renderWorldBlock(worldObj, xCoord, yCoord, zCoord, getBlockType(), 0, render);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderPart(RenderBlocks render, int part) {
		render(render);
	}

	@Override
	public List<ItemStack> removePartByPlayer(EntityPlayer ply, int part) {
		if (cover != null)
			cover.convertToContainerBlock();
		else
			worldObj.setBlock(xCoord, yCoord, zCoord, 0, 0, 3);
		return Collections.singletonList(new ItemStack(ProjectRed.blockGate, 1, getType().ordinal()));
	}

	@Override
	public void onRedstoneInputChanged() {
		updateLogic(false, false);
	}

	@Override
	public boolean connects(IWire wire, int blockFace, int fromDirection) {
		if (blockFace != side)
			return false;

		if (logic == null)
			return false;

		int rel = absToRelDirection(fromDirection);
		if (rel < 0)
			return false;

		boolean bundled = (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel));

		if (!(bundled ? wire instanceof IBundledWire : wire instanceof IRedstoneWire))
			return false;

		return logic.connectsToDirection(rel);
	}

	@Override
	public boolean connectsAroundCorner(IWire wire, int blockFace, int fromDirection) {
		return false;
	}

	private byte[] returnedBundledCableStrength;

	@Override
	public byte[] getBundledCableStrength(int blockFace, int toDirection) {
		if (!hasBundledConnections)
			return null;

		if (blockFace != side)
			return null;

		int rel = absToRelDirection(toDirection);
		if (rel < 0)
			return null;

		if (!((GateLogic.WithBundledConnections) logic).isBundledConnection(rel))
			return null;

		if (returnedBundledCableStrength == null)
			returnedBundledCableStrength = new byte[16];

		short bitmask = prevOutputs[rel];
		for (int k = 0; k < 16; k++) {
			returnedBundledCableStrength[k] = ((bitmask & 1) != 0) ? (byte) 255 : 0;
			bitmask >>= 1;
		}

		return returnedBundledCableStrength;
	}

	@Override
	public void onBundledInputChanged() {
		if (hasBundledConnections)
			onRedstoneInputChanged();
	}

	public void onNeighborChanged() {
		checkSupport();
	}

	/**
	 * See if the gate is still attached to something.
	 */
	public void checkSupport() {
		if (BasicUtils.isClient(worldObj)) {
			return;
		}
		Coords localCoord = new Coords(this);
		localCoord.orientation = ForgeDirection.getOrientation(this.getSide());
		localCoord.moveForwards(1);
		Block supporter = Block.blocksList[worldObj.getBlockId(localCoord.x, localCoord.y, localCoord.z)];
		if (!BasicWireUtils.canPlaceWireOnSide(worldObj, localCoord.x, localCoord.y, localCoord.z, localCoord.orientation.getOpposite(), false)) {
			int id = worldObj.getBlockId(xCoord, yCoord, zCoord);
			Block gate = Block.blocksList[id];
			if (gate != null) {
				ItemStack dropped = new ItemStack(ProjectRed.blockGate, 1, type.ordinal());
				BasicUtils.dropItemFromLocation(worldObj, dropped, false, null, getSide(), 10, new Coords(this));
				removeGate();
			}
		}
	}

	public void removeGate() {
		if (cover != null) {
			cover.convertToContainerBlock();
		} else {
			worldObj.setBlock(xCoord, yCoord, zCoord, 0, 0, 3);
		}
	}

	/**
	 * We need the entire block to render if we are looking near it, because
	 * there are things outside of the default BBox like torches and pointers.
	 */
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return AxisAlignedBB.getAABBPool().getAABB(xCoord, yCoord, zCoord, xCoord + 1, yCoord + 1, zCoord + 1);
	}
}
