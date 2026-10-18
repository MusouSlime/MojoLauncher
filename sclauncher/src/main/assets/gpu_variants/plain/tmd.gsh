#version 330 core
// PLAIN-varyings probe for RG406H (Mali-G57): stock tmd.gsh logic with two
// changes:
//  1. VS->GS->FS varyings are scalar declarations, not VS_OUT/GS_OUT
//     interface blocks (same driver rationale as plain/tmd.vsh). GS inputs
//     keep the VS names (vertUv[] ...); GS outputs use g-prefixed names
//     (gVertUv ...) matched by plain/tmd.fsh, because inputs and outputs
//     share one namespace inside the GS stage.
//  2. Near-plane cull runs on view-space Z RECOVERED from clip positions +
//     inverse(projection) (proven by the v1-v3 DEBUG probes and the v8
//     FIXED shader), with fallback to the stock varying if the transforms
//     UBO reads dead. Quad-adjacency branch omitted like FIXED (vertFlags
//     unreadable on Mali; stock only ever emits verts 0/2/4).
// Unlike WHITEOUT this forwards the REAL varyings, so textured models
// should appear with correct colours if scalar transport is clean.
layout (triangles_adjacency) in;
layout (triangle_strip, max_vertices = 3) out;

smooth in vec2 vertUv[];
flat in vec2 vertTpage[];
flat in vec2 vertClut[];
flat in int vertBpp[];
smooth in vec4 vertColour[];
flat in int vertFlags[];

flat in int translucency[];

flat in float widthMultiplier[];
flat in int widthMask[];
flat in int indexShift[];
flat in int indexMask[];

smooth in float viewspaceZ[];

smooth in float depth[];
smooth in float depthOffset[];

smooth out vec2 gVertUv;
flat out vec2 gVertTpage;
flat out vec2 gVertClut;
flat out int gVertBpp;
smooth out vec4 gVertColour;
flat out int gVertFlags;

flat out int gTranslucency;

flat out float gWidthMultiplier;
flat out int gWidthMask;
flat out int gIndexShift;
flat out int gIndexMask;

smooth out float gDepth;
smooth out float gDepthOffset;

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

void emit(int, float);

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
    z0 = viewspaceZ[0];
    z2 = viewspaceZ[2];
    z4 = viewspaceZ[4];
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

  emit(0, avgDepth);
  emit(2, avgDepth);
  emit(4, avgDepth);
  EndPrimitive();
}

void emit(int i, float ps1depth) {
  gVertUv = vertUv[i];
  gVertTpage = vertTpage[i];
  gVertClut = vertClut[i];
  gVertBpp = vertBpp[i];
  gVertColour = vertColour[i];
  gVertFlags = vertFlags[i];
  gTranslucency = translucency[i];
  gWidthMultiplier = widthMultiplier[i];
  gWidthMask = widthMask[i];
  gIndexShift = indexShift[i];
  gIndexMask = indexMask[i];
  gDepth = depth[i];
  gDepthOffset = depthOffset[i];

  gl_Position = gl_in[i].gl_Position;

  if(usePs1Depth != 0) {
    gl_Position.z = ps1depth;
  }

  EmitVertex();
}
