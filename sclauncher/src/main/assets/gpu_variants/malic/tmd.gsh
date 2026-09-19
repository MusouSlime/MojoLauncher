#version 330 core
// MALI-C TEST VARIANT (RG406H experiment, single variable vs Mali-B):
// identical to malib/tmd.gsh (near-plane culls still disabled) EXCEPT the
// emit(int) helper is gone — every vertex is copied with CONSTANT indices
// only (vs_out[0]/[2]/[4], gl_in[0]/[2]/[4]). Hypothesis under test:
// Mali-G57 miscompiles DYNAMIC indexing of geometry-shader input arrays
// (emit(i) with i decided at runtime), so vs_out[i].vertFlags comes back
// wrong (RED instead of GREEN) AND gl_in[i].gl_Position comes back wrong
// (screen-spanning spikes) from the same bad fetch. If Mali-C renders all
// GREEN with no spikes, the dynamic index is the culprit — bake the unroll
// into the stock shader and drop the GPU-mode scaffolding.
layout (triangles_adjacency) in;
layout (triangle_strip, max_vertices = 3) out;

in VS_OUT {
  smooth vec2 vertUv;
  flat vec2 vertTpage;
  flat vec2 vertClut;
  flat int vertBpp;
  smooth vec4 vertColour;
  flat int vertFlags;

  flat int translucency;

  flat float widthMultiplier;
  flat int widthMask;
  flat int indexShift;
  flat int indexMask;

  smooth float viewspaceZ;

  smooth float depth;
  smooth float depthOffset;
} vs_out[];

out GS_OUT {
  smooth vec2 vertUv;
  flat vec2 vertTpage;
  flat vec2 vertClut;
  flat int vertBpp;
  smooth vec4 vertColour;
  flat int vertFlags;

  flat int translucency;

  flat float widthMultiplier;
  flat int widthMask;
  flat int indexShift;
  flat int indexMask;

  smooth float depth;
  smooth float depthOffset;
} gs_out;

layout(std140) uniform projectionInfo {
  float znear;
  /** PS1 projection plane distance (H) */
  float zfar;
  float zdiffInv;
  /** 0: ortho, 1: PS1 perspective, 2: modern perspective */
  float projectionMode;
};

uniform int usePs1Depth;

void main() {
  // MALI TEST: near-plane whole-face cull DISABLED (same as Mali-A/B).
  if(false && zfar >= vs_out[0].viewspaceZ * 2.0) {
    return;
  }

  if(false && zfar >= vs_out[2].viewspaceZ * 2.0) {
    return;
  }

  if(false && zfar >= vs_out[4].viewspaceZ * 2.0) {
    return;
  }

  float avgDepth = gl_in[0].gl_Position.z + gl_in[2].gl_Position.z + gl_in[4].gl_Position.z;
  int vertexCount = 3;

  // Quad, check adjacent vertex (constant index 1 — always was constant).
  if((vs_out[0].vertFlags & 0x10) != 0) {
    if(false && zfar >= vs_out[1].viewspaceZ * 2.0) {
      return;
    }

    avgDepth += gl_in[1].gl_Position.z;
    vertexCount++;
  }

  avgDepth /= vertexCount;

  // MALI-C: unrolled emit — constant indices only, no emit(int) call.
  // Vertex 0.
  gs_out.vertUv = vs_out[0].vertUv;
  gs_out.vertTpage = vs_out[0].vertTpage;
  gs_out.vertClut = vs_out[0].vertClut;
  gs_out.vertBpp = vs_out[0].vertBpp;
  gs_out.vertColour = vs_out[0].vertColour;
  gs_out.vertFlags = vs_out[0].vertFlags;
  gs_out.translucency = vs_out[0].translucency;
  gs_out.widthMultiplier = vs_out[0].widthMultiplier;
  gs_out.widthMask = vs_out[0].widthMask;
  gs_out.indexShift = vs_out[0].indexShift;
  gs_out.indexMask = vs_out[0].indexMask;
  gs_out.depth = vs_out[0].depth;
  gs_out.depthOffset = vs_out[0].depthOffset;
  gl_Position = gl_in[0].gl_Position;
  if(usePs1Depth != 0) {
    gl_Position.z = avgDepth;
  }
  EmitVertex();

  // Vertex 2.
  gs_out.vertUv = vs_out[2].vertUv;
  gs_out.vertTpage = vs_out[2].vertTpage;
  gs_out.vertClut = vs_out[2].vertClut;
  gs_out.vertBpp = vs_out[2].vertBpp;
  gs_out.vertColour = vs_out[2].vertColour;
  gs_out.vertFlags = vs_out[2].vertFlags;
  gs_out.translucency = vs_out[2].translucency;
  gs_out.widthMultiplier = vs_out[2].widthMultiplier;
  gs_out.widthMask = vs_out[2].widthMask;
  gs_out.indexShift = vs_out[2].indexShift;
  gs_out.indexMask = vs_out[2].indexMask;
  gs_out.depth = vs_out[2].depth;
  gs_out.depthOffset = vs_out[2].depthOffset;
  gl_Position = gl_in[2].gl_Position;
  if(usePs1Depth != 0) {
    gl_Position.z = avgDepth;
  }
  EmitVertex();

  // Vertex 4.
  gs_out.vertUv = vs_out[4].vertUv;
  gs_out.vertTpage = vs_out[4].vertTpage;
  gs_out.vertClut = vs_out[4].vertClut;
  gs_out.vertBpp = vs_out[4].vertBpp;
  gs_out.vertColour = vs_out[4].vertColour;
  gs_out.vertFlags = vs_out[4].vertFlags;
  gs_out.translucency = vs_out[4].translucency;
  gs_out.widthMultiplier = vs_out[4].widthMultiplier;
  gs_out.widthMask = vs_out[4].widthMask;
  gs_out.indexShift = vs_out[4].indexShift;
  gs_out.indexMask = vs_out[4].indexMask;
  gs_out.depth = vs_out[4].depth;
  gs_out.depthOffset = vs_out[4].depthOffset;
  gl_Position = gl_in[4].gl_Position;
  if(usePs1Depth != 0) {
    gl_Position.z = avgDepth;
  }
  EmitVertex();

  EndPrimitive();
}
