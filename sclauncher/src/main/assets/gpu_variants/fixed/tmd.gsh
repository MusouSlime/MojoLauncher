#version 330 core
// WHITEOUT probe for RG406H (Mali-G57): the recovered-z fix (v20260955)
// makes TMD visible, but colors come out as a red/black patchwork.
// Two live hypotheses: (a) textured path sampling garbage (flags/UVs
// misread => random atlas texels), (b) vertColour itself garbled in the
// VS->GS->FS interface-block transport.
// This probe forces flags=0 (untextured, no sampling, no discard) and
// vertColour=white, keeping the recovered-z cull + stock depth/emit:
//   all-white models  => untextured path + colour transport CLEAN =>
//                        corruption comes from the textured path
//                        (flags/UVs) => investigate UV/flag transport next
//   still patchy     => vertColour/FS transport broken even without
//                        sampling => plain-varyings rewrite or de-adjacency
// Compare against the 1.22 (FIXED) screenshots of the same scenes.
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

// Shared with the vertex stage (bound once per program by the engine).
layout(std140) uniform transforms {
  mat4 camera;
  mat4 projection;
};

layout(std140) uniform projectionInfo {
  float znear;
  /** PS1 projection plane distance (H) */
  float zfar;
  float zdiffInv;
  /** 0: ortho, 1: PS1 perspective, 2: modern perspective */
  float projectionMode;
};

uniform int usePs1Depth;
uniform vec2 tpageOverride;

void emitWhiteout(int, float);

float min3(float a, float b, float c) {
  return min(a, min(b, c));
}

// Recover pre-projection view-space Z from a clip-space position.
// Returns -1 for w <= 0 (behind camera; stock culls those too).
float recoverZ(vec4 clip, mat4 invProj) {
  if (clip.w <= 0.0) {
    return -1.0;
  }
  vec4 ndc = vec4(clip.xyz / clip.w, 1.0);
  vec4 view = invProj * ndc;
  if (view.w == 0.0) {
    return -1.0;
  }
  return view.z / view.w;
}

void main() {
  // Keep-alive (same as stock): prevent stripping tpageOverride.
  if (tpageOverride.x > zfar + 9999.0) {
  }

  // View-space Z per vertex: recomputed (Mali) or stock varying (fallback).
  float z0;
  float z2;
  float z4;
  if (projection[0][0] == 0.0 && projection[1][1] == 0.0 && projection[2][2] == 0.0) {
    z0 = vs_out[0].viewspaceZ;
    z2 = vs_out[2].viewspaceZ;
    z4 = vs_out[4].viewspaceZ;
  } else {
    mat4 invProj = inverse(projection);
    z0 = recoverZ(gl_in[0].gl_Position, invProj);
    z2 = recoverZ(gl_in[2].gl_Position, invProj);
    z4 = recoverZ(gl_in[4].gl_Position, invProj);
  }

  // Near-plane cull (stock semantics): too close => cull the whole face.
  if(zfar >= z0 * 2.0) {
    return;
  }

  if(zfar >= z2 * 2.0) {
    return;
  }

  if(zfar >= z4 * 2.0) {
    return;
  }

  float avgDepth = gl_in[0].gl_Position.z + gl_in[2].gl_Position.z + gl_in[4].gl_Position.z;
  avgDepth /= 3.0;

  emitWhiteout(0, avgDepth);
  emitWhiteout(2, avgDepth);
  emitWhiteout(4, avgDepth);
  EndPrimitive();
}

void emitWhiteout(int i, float depth) {
  gs_out.vertUv = vs_out[i].vertUv;
  gs_out.vertTpage = vs_out[i].vertTpage;
  gs_out.vertClut = vs_out[i].vertClut;
  gs_out.vertBpp = vs_out[i].vertBpp;
  gs_out.vertColour = vec4(1.0, 1.0, 1.0, 1.0);
  // Force untextured + opaque: no sampling, no translucency discard.
  gs_out.vertFlags = 0;
  gs_out.translucency = vs_out[i].translucency;
  gs_out.widthMultiplier = vs_out[i].widthMultiplier;
  gs_out.widthMask = vs_out[i].widthMask;
  gs_out.indexShift = vs_out[i].indexShift;
  gs_out.indexMask = vs_out[i].indexMask;
  gs_out.depth = vs_out[i].depth;
  gs_out.depthOffset = vs_out[i].depthOffset;

  gl_Position = gl_in[i].gl_Position;

  if(usePs1Depth != 0) {
    gl_Position.z = depth;
  }

  EmitVertex();
}
