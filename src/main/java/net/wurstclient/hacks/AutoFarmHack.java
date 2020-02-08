/*
 * Copyright (C) 2014 - 2020 | Alexander01998 | All rights reserved.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.util.EntityUtils;
import org.lwjgl.opengl.GL11;
import net.minecraft.block.*;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"auto farm", "AutoHarvest", "auto harvest"})
public final class AutoFarmHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 60, 0.05, ValueDisplay.DECIMAL);
	
	private final HashMap<BlockPos, Item> plants = new HashMap<>();
	
	private final ArrayDeque<Set<BlockPos>> prevBlocks = new ArrayDeque<>();
	private ArrayList<Entity> items = new ArrayList<Entity>(); 
	private BlockPos currentBlock;
	private BlockPos closestChest;
	private float progress;
	private float prevProgress;
	
	private int displayList;
	private int box;
	private int node;
	private PathFinder pathFinder;  
	private PathProcessor processor;
	ItemEntity targetEntity = null;
	private int ticksProcessing;
	private final ArrayList<ItemEntity> groundItems = new ArrayList<>();
	boolean hasInit = false;
	String LogText = "Testing123";
	
	public AutoFarmHack()
	{
		super("AutoFarm",
			"Harvests and re-plants crops automatically.\n"
				+ "Works with wheat, carrots, potatoes, beetroots,\n"
				+ "pumpkins, melons, cacti, sugar canes, kelp and\n"
				+ "nether warts.");
		setCategory(Category.BLOCKS);
		addSetting(range);
	}
	
	@Override
	public void onEnable()
	{
		plants.clear();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		
		displayList = GL11.glGenLists(1);
		box = GL11.glGenLists(1);
		node = GL11.glGenLists(1);
		
		GL11.glNewList(box, GL11.GL_COMPILE);
		Box box = new Box(1 / 16.0, 1 / 16.0, 1 / 16.0, 15 / 16.0, 15 / 16.0,
			15 / 16.0);
		RenderUtils.drawOutlinedBox(box);
		GL11.glEndList();
		
		GL11.glNewList(node, GL11.GL_COMPILE);
		Box node = new Box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		GL11.glBegin(GL11.GL_LINES);
		RenderUtils.drawNode(node);
		GL11.glEnd();
		GL11.glEndList();
		
		pathFinder = new PathFinder(MC.player.getBlockPos());
		
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		if(currentBlock != null)
		{
			IMC.getInteractionManager().setBreakingBlock(true);
			MC.interactionManager.cancelBlockBreaking();
			currentBlock = null;
		}
		
		prevBlocks.clear();
		GL11.glDeleteLists(displayList, 1);
		GL11.glDeleteLists(box, 1);
		GL11.glDeleteLists(node, 1);
	//	PathProcessor.releaseControls();
		pathFinder = null;
		processor = null;
		PathProcessor.releaseControls();
	}
	public void WalkToPosition(BlockPos pos) 
	{		
		if((processor == null || processor.isDone() || ticksProcessing >= 10
				|| !pathFinder.isPathStillValid(processor.getIndex()))
				&& (pathFinder.isDone() || pathFinder.isFailed()))
			{
				pathFinder = new PathFinder(pos);
				processor = null;
				ticksProcessing = 0;
			}
			
			// find path
			if(!pathFinder.isDone() && !pathFinder.isFailed())
			{
				PathProcessor.lockControls();
				pathFinder.think();
				pathFinder.formatPath();
				processor = pathFinder.getProcessor();
			}
			// process path
			if(!processor.isDone())
			{
				processor.process();
				ticksProcessing++;
			}		
		
		
	}
	
	
	// find chest block.. 
	public boolean isChest(BlockPos pos)
	{
		return false;
	}
	
	@Override
	public void onUpdate()
	{
		// on wait period call this
		//WURST.getHax().bonemealAuraHack.setEnabled(true);
		//WURST.getHax().bonemealAuraHack.setEnabled(false);
		//
		
		currentBlock = null;
		Vec3d eyesVec = RotationUtils.getEyesPos().subtract(0.5, 0.5, 0.5);
		BlockPos eyesBlock = new BlockPos(RotationUtils.getEyesPos());
		
		double rangeSq = Math.pow(range.getValue(), 2);
		
		
		int blockRange = (int)Math.ceil(range.getValue());
		
		List<BlockPos> blocks = getBlockStream(eyesBlock, blockRange)
			.filter(pos -> eyesVec.squaredDistanceTo(new Vec3d(pos)) <= rangeSq)
			.filter(pos -> BlockUtils.canBeClicked(pos))
			.collect(Collectors.toList());
		//List<BlockPos> chests = getBlockStream(eyesBlock, blockRange)
		//		.filter(pos -> eyesVec.squaredDistanceTo(new Vec3d(pos)) <= rangeSq)
		//		.filter(pos -> BlockUtils.canBeClicked(pos))
		//		.filter(this::isChest)
		//		.collect(Collectors.toList());
		
		
		
		
		registerPlants(blocks);
		
		List<BlockPos> blocksToHarvest = new ArrayList<>();
		List<BlockPos> blocksToReplant = new ArrayList<>();
		
		if(!WURST.getHax().freecamHack.isEnabled()) // no freecam check
		{
			blocksToHarvest =
				blocks.parallelStream().filter(this::shouldBeHarvested)
					.sorted(Comparator.comparingDouble(
						pos -> eyesVec.squaredDistanceTo(new Vec3d(pos))))
					.collect(Collectors.toList());
			
			blocksToReplant = getBlockStream(eyesBlock, blockRange)	.filter(pos -> plants.containsKey(pos))
				.filter(
					pos -> eyesVec.squaredDistanceTo(new Vec3d(pos)) <= rangeSq)
				.filter(pos -> BlockUtils.getState(pos).getMaterial()
					.isReplaceable())
				.filter(this::canBeReplanted)
				.sorted(Comparator.comparingDouble(
					pos -> eyesVec.squaredDistanceTo(new Vec3d(pos))))
				.collect(Collectors.toList());
		}
		
		while(!blocksToReplant.isEmpty())
		{
			BlockPos pos = blocksToReplant.get(0);
			Item neededItem = plants.get(pos);
			if(tryToReplant(pos, Items.CARROT))
				break;
			
			blocksToReplant.removeIf(p -> plants.get(p) == neededItem);
		}
		
		BlockPos targetBlockForAI = null;
		
		
		if(blocksToReplant.isEmpty())
		{
		
			ClientPlayerEntity player = MC.player;
			ItemStack heldItem = player.getMainHandStack();
			boolean hasFoundBonemeal = false;
			
			// check if we have selected bonemeal
			if(heldItem.isEmpty() || !(heldItem.getItem() instanceof BoneMealItem))
			{
				// if not, iterate through inv
				for(int slot = 0; slot < 36; slot++)
				{	
					// are we looking at our own hand item?
					if(slot == player.inventory.selectedSlot)
						continue;
					// check if we have switched to the correct item.. 
					ItemStack stack = player.inventory.getInvStack(slot);
					if(!(stack.getItem() instanceof BoneMealItem) || stack.isEmpty()) 
					{
						continue;
					}
					
					// switch items in an iterative fashion..
					if(slot < 9) // hand slots 
						player.inventory.selectedSlot = slot;
					else if(player.inventory.getEmptySlot() < 9)
						IMC.getInteractionManager().windowClick_QUICK_MOVE(slot);
					else if(player.inventory.getEmptySlot() != -1)
					{
						IMC.getInteractionManager()
							.windowClick_QUICK_MOVE(player.inventory.selectedSlot + 36);
						IMC.getInteractionManager().windowClick_QUICK_MOVE(slot);
					}else
					{
						IMC.getInteractionManager()
							.windowClick_PICKUP(player.inventory.selectedSlot + 36);
						IMC.getInteractionManager().windowClick_PICKUP(slot);
						IMC.getInteractionManager()
							.windowClick_PICKUP(player.inventory.selectedSlot + 36);
					}
					hasFoundBonemeal = true;
				}
			}else 
				hasFoundBonemeal = true;

			
			
			
			boolean shouldPlantSeeds = false;
			boolean shouldHarvest = false;
			// look nearby and look for empty blocks.. 
			if (blocksToReplant.isEmpty())
				shouldPlantSeeds = false;
			else 
			{	
				double minDist = 999999;
				
				for(BlockPos pos : blocksToReplant)
				{
					double curDist = pos.getSquaredDistance(MC.player.getBlockPos());
					if (minDist < curDist) {
						targetBlockForAI = pos;
						shouldPlantSeeds = true;
					}
				}
			}
			
		
			
			
			boolean shouldPickupObjects = false;		
			if (shouldPlantSeeds == false && shouldHarvest == false)
			{
				shouldPickupObjects = true;
			}
			
			
			if (shouldPickupObjects)
			{
				groundItems.clear();
				for(Entity entity : MC.world.getEntities())
					if(entity instanceof ItemEntity)
						groundItems.add((ItemEntity)entity);				
				
				// look for entities.. 
				float min = 10000000f;
				ItemEntity ClosestItem = null;
				for(ItemEntity e : groundItems)
				{
					if (e.onGround && (e.getDisplayName().toString().toLowerCase().contains("carrot") || e.getName().toString().toLowerCase().contains("carrot")))
					{
						float curDist = (float)e.squaredDistanceTo(MC.player);
					
						if (curDist > 1000)
							continue;
					
						if (curDist < min)
						{
							min = curDist;
							targetBlockForAI = e.getBlockPos();
						}
					}
				}
			}				
			WURST.getHax().bonemealAuraHack.setEnabled(true);
			
			harvest(blocksToHarvest);
			
		}
		else {
			WURST.getHax().bonemealAuraHack.setEnabled(false);
			//if (!shouldPlantSeeds)
			{

				double minDist = 999999;
				
				for(BlockPos pos : blocksToHarvest)
				{
					double curDist = pos.getSquaredDistance(MC.player.getBlockPos());
					if (minDist < curDist) {
						targetBlockForAI = pos;
						//s/houldHarvest = true;
					}
				}
			}
		}
		
		
		
		if (targetBlockForAI != null)
			WalkToPosition(targetBlockForAI);
		else 
			WalkToPosition(MC.player.getBlockPos());
		
		updateDisplayList(blocksToHarvest, blocksToReplant);
	}
	
	private void drawString(String s)
	{
		TextRenderer tr = WurstClient.MC.textRenderer;
		int posX;
		int posY = 100;

		int screenWidth = WurstClient.MC.getWindow().getScaledWidth();
		posX = screenWidth / 2;

		
		float[] acColor = WurstClient.INSTANCE.getGui().getAcColor();
		int textColor = 0x04 << 24 | (int)(acColor[0] * 256) << 16
			| (int)(acColor[1] * 256) << 8 | (int)(acColor[2] * 256);
		
		
		
		tr.draw(s, posX + 1, posY + 1, 0xff000000);
		tr.draw(s, posX, posY, textColor | 0xff000000);
	}
	
	
	@Override
	public void onRender(float partialTicks)
	{
		// GL settings
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glEnable(GL11.GL_LINE_SMOOTH);
		GL11.glLineWidth(2);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glPushMatrix();
		
		RenderUtils.applyRenderOffset();
		
		drawString(LogText);
		GL11.glCallList(displayList);
		if (!pathFinder.isDone())
		{
			PathCmd pathCmd = WURST.getCmds().pathCmd;
			pathFinder.renderPath(pathCmd.isDebugMode(), pathCmd.isDepthTest());
		}
		if(currentBlock != null)
		{
			GL11.glPushMatrix();
			
			Box box = new Box(BlockPos.ORIGIN);
			float p = prevProgress + (progress - prevProgress) * partialTicks;
			float red = p * 2F;
			float green = 2 - red;
			
			GL11.glTranslated(currentBlock.getX(), currentBlock.getY(),
				currentBlock.getZ());
			if(p < 1)
			{
				GL11.glTranslated(0.5, 0.5, 0.5);
				GL11.glScaled(p, p, p);
				GL11.glTranslated(-0.5, -0.5, -0.5);
			}
			
			GL11.glColor4f(red, green, 0, 0.25F);
			RenderUtils.drawSolidBox(box);
			
			GL11.glColor4f(red, green, 0, 0.5F);
			RenderUtils.drawOutlinedBox(box);
			
			GL11.glPopMatrix();
		}
		
		GL11.glPopMatrix();
		
		// GL resets
		GL11.glColor4f(1, 1, 1, 1);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_LINE_SMOOTH);
	}
	
	private Stream<BlockPos> getBlockStream(BlockPos center, int range)
	{
		BlockPos min = center.add(-range, -range, -range);
		BlockPos max = center.add(range, range, range);
		
		return BlockUtils.getAllInBox(min, max).stream();
	}
	
	private boolean shouldBeHarvested(BlockPos pos)
	{
		Block block = BlockUtils.getBlock(pos);
		BlockState state = BlockUtils.getState(pos);
		
		if(block instanceof CropBlock)
			return ((CropBlock)block).isMature(state);
		else if(block instanceof GourdBlock)
			return true;
		else if(block instanceof SugarCaneBlock)
			return BlockUtils.getBlock(pos.down()) instanceof SugarCaneBlock
				&& !(BlockUtils
					.getBlock(pos.down(2)) instanceof SugarCaneBlock);
		else if(block instanceof CactusBlock)
			return BlockUtils.getBlock(pos.down()) instanceof CactusBlock
				&& !(BlockUtils.getBlock(pos.down(2)) instanceof CactusBlock);
		else if(block instanceof KelpPlantBlock)
			return BlockUtils.getBlock(pos.down()) instanceof KelpPlantBlock
				&& !(BlockUtils
					.getBlock(pos.down(2)) instanceof KelpPlantBlock);
		else if(block instanceof NetherWartBlock)
			return state.get(NetherWartBlock.AGE) >= 3;

		return false;
	}
	
	private void registerPlants(List<BlockPos> blocks)
	{
		HashMap<Block, Item> seeds = new HashMap<>();
		seeds.put(Blocks.WHEAT, Items.WHEAT_SEEDS);
		seeds.put(Blocks.CARROTS, Items.CARROT);
		seeds.put(Blocks.POTATOES, Items.POTATO);
		seeds.put(Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
		seeds.put(Blocks.PUMPKIN_STEM, Items.PUMPKIN_SEEDS);
		seeds.put(Blocks.MELON_STEM, Items.MELON_SEEDS);
		seeds.put(Blocks.NETHER_WART, Items.NETHER_WART);
		
		plants.putAll(blocks.parallelStream()
			.filter(pos -> seeds.containsKey(BlockUtils.getBlock(pos)))
			.collect(Collectors.toMap(pos -> pos,
				pos -> seeds.get(BlockUtils.getBlock(pos)))));
	}
	
	private boolean canBeReplanted(BlockPos pos)
	{
		return BlockUtils.getBlock(pos.down()) instanceof FarmlandBlock;
	}
	
	private boolean tryToReplant(BlockPos pos, Item neededItem)
	{
		ClientPlayerEntity player = MC.player;
		ItemStack heldItem = player.getMainHandStack();
		
		if(!heldItem.isEmpty() && heldItem.getItem() == neededItem)
		{
			placeBlockSimple(pos);
			return true;
		}
		
		for(int slot = 0; slot < 36; slot++)
		{
			if(slot == player.inventory.selectedSlot)
				continue;
			
			ItemStack stack = player.inventory.getInvStack(slot);
			if(stack.isEmpty() || stack.getItem() != neededItem)
				continue;
			
			if(slot < 9)
				player.inventory.selectedSlot = slot;
			else if(player.inventory.getEmptySlot() < 9)
				IMC.getInteractionManager().windowClick_QUICK_MOVE(slot);
			else if(player.inventory.getEmptySlot() != -1)
			{
				IMC.getInteractionManager()
					.windowClick_QUICK_MOVE(player.inventory.selectedSlot + 36);
				IMC.getInteractionManager().windowClick_QUICK_MOVE(slot);
			}else
			{
				IMC.getInteractionManager()
					.windowClick_PICKUP(player.inventory.selectedSlot + 36);
				IMC.getInteractionManager().windowClick_PICKUP(slot);
				IMC.getInteractionManager()
					.windowClick_PICKUP(player.inventory.selectedSlot + 36);
			}
			
			return true;
		}
		
		return false;
	}
	
	private void placeBlockSimple(BlockPos pos)
	{
		Direction side = null;
		Direction[] sides = Direction.values();
		
		Vec3d eyesPos = RotationUtils.getEyesPos();
		Vec3d posVec = new Vec3d(pos).add(0.5, 0.5, 0.5);
		double distanceSqPosVec = eyesPos.squaredDistanceTo(posVec);
		
		Vec3d[] hitVecs = new Vec3d[sides.length];
		for(int i = 0; i < sides.length; i++)
			hitVecs[i] =
				posVec.add(new Vec3d(sides[i].getVector()).multiply(0.5));
		
		for(int i = 0; i < sides.length; i++)
		{
			// check if neighbor can be right clicked
			BlockPos neighbor = pos.offset(sides[i]);
			if(!BlockUtils.canBeClicked(neighbor))
				continue;
			
			// check line of sight
			BlockState neighborState = BlockUtils.getState(neighbor);
			VoxelShape neighborShape =
				neighborState.getOutlineShape(MC.world, neighbor);
			if(MC.world.rayTraceBlock(eyesPos, hitVecs[i], neighbor,
				neighborShape, neighborState) != null)
				continue;
			
			side = sides[i];
			break;
		}
		
		if(side == null)
			for(int i = 0; i < sides.length; i++)
			{
				// check if neighbor can be right clicked
				if(!BlockUtils.canBeClicked(pos.offset(sides[i])))
					continue;
				
				// check if side is facing away from player
				if(distanceSqPosVec > eyesPos.squaredDistanceTo(hitVecs[i]))
					continue;
				
				side = sides[i];
				break;
			}
		
		if(side == null)
			return;
		
		Vec3d hitVec = hitVecs[side.ordinal()];
		
		// face block
		WURST.getRotationFaker().faceVectorPacket(hitVec);
		if(RotationUtils.getAngleToLastReportedLookVec(hitVec) > 1)
			return;
		
		// check timer
		if(IMC.getItemUseCooldown() > 0)
			return;
		
		// place block
		IMC.getInteractionManager().rightClickBlock(pos.offset(side),
			side.getOpposite(), hitVec);
		
		// swing arm
		MC.player.networkHandler
			.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
		
		// reset timer
		IMC.setItemUseCooldown(4);
	}
	
	private void harvest(List<BlockPos> blocksToHarvest)
	{
		if(MC.player.abilities.creativeMode)
		{
			Stream<BlockPos> stream3 = blocksToHarvest.parallelStream();
			for(Set<BlockPos> set : prevBlocks)
				stream3 = stream3.filter(pos -> !set.contains(pos));
			List<BlockPos> blocksToHarvest2 =
				stream3.collect(Collectors.toList());
			
			prevBlocks.addLast(new HashSet<>(blocksToHarvest2));
			while(prevBlocks.size() > 5)
				prevBlocks.removeFirst();
			
			if(!blocksToHarvest2.isEmpty())
				currentBlock = blocksToHarvest2.get(0);
			
			MC.interactionManager.cancelBlockBreaking();
			progress = 1;
			prevProgress = 1;
			BlockBreaker.breakBlocksWithPacketSpam(blocksToHarvest2);
			return;
		}
		
		for(BlockPos pos : blocksToHarvest)
			if(BlockBreaker.breakOneBlock(pos))
			{
				currentBlock = pos;
				break;
			}
		
		if(currentBlock == null)
			MC.interactionManager.cancelBlockBreaking();
		
		if(currentBlock != null && BlockUtils.getHardness(currentBlock) < 1)
		{
			prevProgress = progress;
			progress = IMC.getInteractionManager().getCurrentBreakingProgress();
			
			if(progress < prevProgress)
				prevProgress = progress;
			
		}else
		{
			progress = 1;
			prevProgress = 1;
		}
	}
	
	private void updateDisplayList(List<BlockPos> blocksToHarvest,
		List<BlockPos> blocksToReplant)
	{
		GL11.glNewList(displayList, GL11.GL_COMPILE);
		GL11.glColor4f(0, 1, 0, 0.5F);
		for(BlockPos pos : blocksToHarvest)
		{
			GL11.glPushMatrix();
			GL11.glTranslated(pos.getX(), pos.getY(), pos.getZ());
			GL11.glCallList(box);
			GL11.glPopMatrix();
		}
		GL11.glColor4f(0, 1, 1, 0.5F);
		for(BlockPos pos : plants.keySet())
		{
			GL11.glPushMatrix();
			GL11.glTranslated(pos.getX(), pos.getY(), pos.getZ());
			GL11.glCallList(node);
			GL11.glPopMatrix();
		}
		GL11.glColor4f(1, 0, 0, 0.5F);
		for(BlockPos pos : blocksToReplant)
		{
			GL11.glPushMatrix();
			GL11.glTranslated(pos.getX(), pos.getY(), pos.getZ());
			GL11.glCallList(box);
			GL11.glPopMatrix();
		}
		GL11.glEndList();
	}

}
