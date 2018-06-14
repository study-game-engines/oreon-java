package org.oreon.vk.components.ui;

import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DEPENDENCY_BY_REGION_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;
import org.oreon.common.ui.GUI;
import org.oreon.common.ui.UIPanelLoader;
import org.oreon.core.context.EngineContext;
import org.oreon.core.model.Mesh;
import org.oreon.core.model.Vertex.VertexLayout;
import org.oreon.core.scenegraph.NodeComponentType;
import org.oreon.core.scenegraph.RenderList;
import org.oreon.core.util.BufferUtil;
import org.oreon.core.util.MeshGenerator;
import org.oreon.core.vk.command.CommandBuffer;
import org.oreon.core.vk.command.SubmitInfo;
import org.oreon.core.vk.context.DeviceManager.DeviceType;
import org.oreon.core.vk.context.VkContext;
import org.oreon.core.vk.descriptor.DescriptorSet;
import org.oreon.core.vk.descriptor.DescriptorSetLayout;
import org.oreon.core.vk.device.LogicalDevice;
import org.oreon.core.vk.framebuffer.FrameBufferColorAttachment;
import org.oreon.core.vk.framebuffer.VkFrameBuffer;
import org.oreon.core.vk.framebuffer.VkFrameBufferObject;
import org.oreon.core.vk.image.VkImage;
import org.oreon.core.vk.image.VkImageView;
import org.oreon.core.vk.image.VkSampler;
import org.oreon.core.vk.memory.VkBuffer;
import org.oreon.core.vk.pipeline.RenderPass;
import org.oreon.core.vk.pipeline.ShaderPipeline;
import org.oreon.core.vk.pipeline.VkPipeline;
import org.oreon.core.vk.pipeline.VkVertexInput;
import org.oreon.core.vk.scenegraph.VkRenderInfo;
import org.oreon.core.vk.util.VkUtil;
import org.oreon.core.vk.wrapper.buffer.VkBufferHelper;
import org.oreon.core.vk.wrapper.buffer.VkMeshBuffer;
import org.oreon.core.vk.wrapper.command.PrimaryCmdBuffer;
import org.oreon.core.vk.wrapper.command.SecondaryDrawIndexedCmdBuffer;
import org.oreon.core.vk.wrapper.image.VkImageBundle;
import org.oreon.core.vk.wrapper.image.VkImageHelper;
import org.oreon.core.vk.wrapper.pipeline.GraphicsPipeline;

import lombok.Getter;

public class VkGUI extends GUI{

	@Getter
	protected VkFrameBufferObject guiOverlayFbo;
	
	protected VkImageBundle fontsImageBundle;
	protected VkMeshBuffer panelMeshBuffer;
	
	private PrimaryCmdBuffer guiPrimaryCmdBuffer;
	private LinkedHashMap<String, CommandBuffer> guiSecondaryCmdBuffers;
	private RenderList guiRenderList;
	private SubmitInfo guiSubmitInfo;
	private VkQueue queue;
	
	// fullscreen underlay image resources
	private CommandBuffer underlayImageCmdBuffer;
	private VkPipeline underlayImagePipeline;
	private DescriptorSet underlayImageDescriptorSet;
	private DescriptorSetLayout underlayImageDescriptorSetLayout;
	private VkSampler underlayImageSampler;
	
	public void init(VkImageView underlayImageView) {

		LogicalDevice device = VkContext.getDeviceManager()
				.getLogicalDevice(DeviceType.MAJOR_GRAPHICS_DEVICE);
		VkPhysicalDeviceMemoryProperties memoryProperties = VkContext.getDeviceManager()
				.getPhysicalDevice(DeviceType.MAJOR_GRAPHICS_DEVICE).getMemoryProperties();
		queue = device.getGraphicsQueue();
		
		guiOverlayFbo = new SingleAttachmentFbo(device.getHandle(), memoryProperties);
		
		guiRenderList = new RenderList();
		guiSecondaryCmdBuffers = new LinkedHashMap<String, CommandBuffer>();
		
		guiPrimaryCmdBuffer =  new PrimaryCmdBuffer(device.getHandle(),
				device.getGraphicsCommandPool().getHandle());
		guiSubmitInfo = new SubmitInfo();
		guiSubmitInfo.setCommandBuffers(guiPrimaryCmdBuffer.getHandlePointer());
		
		// fonts Image 
		VkImage fontsImage = VkImageHelper.loadImageFromFile(
				device.getHandle(), memoryProperties,
				device.getTransferCommandPool().getHandle(),
				device.getTransferQueue(),
				"gui/tex/Fonts.png",
				VK_IMAGE_USAGE_SAMPLED_BIT,
				VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_ACCESS_SHADER_READ_BIT,
				VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
				VK_QUEUE_FAMILY_IGNORED);
		
		VkImageView fontsImageView = new VkImageView(device.getHandle(),
				VK_FORMAT_R8G8B8A8_UNORM, fontsImage.getHandle(), 
				VK_IMAGE_ASPECT_COLOR_BIT, 1);
		
		VkSampler sampler = new VkSampler(device.getHandle(), VK_FILTER_LINEAR, false, 1,
				VK_SAMPLER_MIPMAP_MODE_NEAREST, 0, VK_SAMPLER_ADDRESS_MODE_REPEAT);
		
		fontsImageBundle = new VkImageBundle(fontsImage, fontsImageView, sampler);
		
		// panel mesh buffer
		panelMeshBuffer = new VkMeshBuffer(device.getHandle(),
				memoryProperties, device.getTransferCommandPool(), device.getTransferQueue(),
				UIPanelLoader.load("gui/basicPanel.gui"), VertexLayout.POS2D);
		
		// fullscreen underlay Image resources
		ShaderPipeline shaderPipeline = new ShaderPipeline(device.getHandle());
	    shaderPipeline.createVertexShader("shaders/quad/quad.vert.spv");
	    shaderPipeline.createFragmentShader("shaders/quad/quad.frag.spv");
	    shaderPipeline.createShaderPipeline();
	    
	    VkVertexInput vertexInputInfo = new VkVertexInput(VertexLayout.POS_UV);
		
	    Mesh fullScreenQuad = MeshGenerator.NDCQuad2D();
        ByteBuffer vertexBuffer = BufferUtil.createByteBuffer(fullScreenQuad.getVertices(), VertexLayout.POS_UV);
        ByteBuffer indexBuffer = BufferUtil.createByteBuffer(fullScreenQuad.getIndices());
        
        VkBuffer vertexBufferObject = VkBufferHelper.createDeviceLocalBuffer(
        		device.getHandle(), memoryProperties,
        		device.getTransferCommandPool().getHandle(),
        		device.getTransferQueue(),
        		vertexBuffer, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        
        VkBuffer indexBufferObject = VkBufferHelper.createDeviceLocalBuffer(
        		device.getHandle(), memoryProperties,
        		device.getTransferCommandPool().getHandle(),
        		device.getTransferQueue(),
        		indexBuffer, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        
        underlayImageDescriptorSetLayout = new DescriptorSetLayout(device.getHandle(),1);
        underlayImageDescriptorSetLayout.addLayoutBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
	    						VK_SHADER_STAGE_FRAGMENT_BIT);
        underlayImageDescriptorSetLayout.create();
	    
        underlayImageSampler = new VkSampler(device.getHandle(), VK_FILTER_NEAREST, false, 0, 
	    		VK_SAMPLER_MIPMAP_MODE_NEAREST, 0, VK_SAMPLER_ADDRESS_MODE_REPEAT);
	    
	    underlayImageDescriptorSet = new DescriptorSet(device.getHandle(),
	    		device.getDescriptorPool(Thread.currentThread().getId()).getHandle(),
	    		underlayImageDescriptorSetLayout.getHandlePointer());
	    underlayImageDescriptorSet.updateDescriptorImageBuffer(underlayImageView.getHandle(),
	    		VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
	    		underlayImageSampler.getHandle(), 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        
        List<DescriptorSet> descriptorSets = new ArrayList<DescriptorSet>();
		List<DescriptorSetLayout> descriptorSetLayouts = new ArrayList<DescriptorSetLayout>();
		
		descriptorSets.add(underlayImageDescriptorSet);
		descriptorSetLayouts.add(underlayImageDescriptorSetLayout);
    
        underlayImagePipeline = new GraphicsPipeline(device.getHandle(),
				shaderPipeline, vertexInputInfo, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
				VkUtil.createLongBuffer(descriptorSetLayouts),
				guiOverlayFbo.getWidth(), guiOverlayFbo.getHeight(),
				guiOverlayFbo.getRenderPass().getHandle(),
				guiOverlayFbo.getColorAttachmentCount(), 1);
        
        underlayImageCmdBuffer = new SecondaryDrawIndexedCmdBuffer(
				device.getHandle(),
				device.getGraphicsCommandPool().getHandle(),
				underlayImagePipeline.getHandle(), underlayImagePipeline.getLayoutHandle(),
				guiOverlayFbo.getFrameBuffer().getHandle(),
				guiOverlayFbo.getRenderPass().getHandle(),
				0,
				VkUtil.createLongArray(descriptorSets),
				vertexBufferObject.getHandle(), indexBufferObject.getHandle(),
				fullScreenQuad.getIndices().length);
        
        guiSecondaryCmdBuffers.put("0", underlayImageCmdBuffer);
	}
	
	public void render(){
		
		record(guiRenderList);
		
		for (String key : guiRenderList.getKeySet()) {
			if(!guiSecondaryCmdBuffers.containsKey(key)){
				VkRenderInfo mainRenderInfo = guiRenderList.get(key)
						.getComponent(NodeComponentType.MAIN_RENDERINFO);
				guiSecondaryCmdBuffers.put(key, mainRenderInfo.getCommandBuffer());
			}
		}
		
		// primary render command buffer
		if (!guiRenderList.getObjectList().isEmpty()){
			guiPrimaryCmdBuffer.reset();
			guiPrimaryCmdBuffer.record(guiOverlayFbo.getRenderPass().getHandle(),
					guiOverlayFbo.getFrameBuffer().getHandle(),
					guiOverlayFbo.getWidth(),
					guiOverlayFbo.getHeight(),
					guiOverlayFbo.getColorAttachmentCount(),
					guiOverlayFbo.getDepthAttachment(),
					VkUtil.createPointerBuffer(guiSecondaryCmdBuffers.values()));
			
			guiSubmitInfo.submit(queue);
		}
		
		vkQueueWaitIdle(queue);
	}
	
	private class SingleAttachmentFbo extends VkFrameBufferObject{
		
		public SingleAttachmentFbo(VkDevice device,
				VkPhysicalDeviceMemoryProperties memoryProperties) {
			
			width = EngineContext.getConfig().getX_ScreenResolution();
			height = EngineContext.getConfig().getY_ScreenResolution();
			
			VkImageBundle colorAttachment = new FrameBufferColorAttachment(device, memoryProperties,
					width, height, VK_FORMAT_R16G16B16A16_SFLOAT, 1);
			
			attachments.put(Attachment.COLOR, colorAttachment);
			
			renderPass = new RenderPass(device);
			renderPass.setAttachment(VK_FORMAT_R16G16B16A16_SFLOAT, 1, VK_IMAGE_LAYOUT_UNDEFINED,
					VK_IMAGE_LAYOUT_GENERAL, VK_ATTACHMENT_LOAD_OP_CLEAR);
			
			renderPass.addColorAttachmentReference(0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			renderPass.setSubpassDependency(VK_SUBPASS_EXTERNAL, 0,
					VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
					VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
					VK_ACCESS_MEMORY_READ_BIT,
					VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
					VK_DEPENDENCY_BY_REGION_BIT);
			renderPass.setSubpassDependency(0, VK_SUBPASS_EXTERNAL,
					VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
					VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
					VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
					VK_ACCESS_MEMORY_READ_BIT,
					VK_DEPENDENCY_BY_REGION_BIT);
			renderPass.createSubpass();
			renderPass.createRenderPass();

			depthAttachment = 0;
			colorAttachmentCount = renderPass.getAttachmentCount()-depthAttachment;
			
			LongBuffer pImageViews = memAllocLong(renderPass.getAttachmentCount());
			pImageViews.put(0, attachments.get(Attachment.COLOR).getImageView().getHandle());

			frameBuffer = new VkFrameBuffer(device, width, height, 1, pImageViews, renderPass.getHandle());
		}
	}

}