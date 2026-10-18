#version 330 core
// UV/FLAG PROBE for RG406H (Mali-G57): investigates textured-path corruption.
// Keeps recovered-z cull (positions correct) but modulates colour by UV
// to verify if UV varyings are correctly interpolated/transported.
// Whiteout (flags=0) proved positions and colour transport are clean;
// this probe isolates the UV component of the texturing path.
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

void emitProbe(int i, float depth) {
  gs_out.vertUv = vs_out[i].vertUv;
  gs_out.vertTpage = vs_out[i].vertTpage;
  gs_out.vertClut = vs_out[i].vertClut;
  gs_out.vertBpp = vs_out[i].vertBpp;
  // Probe: modulate colour by UV magnitude to detect UV transport issues.
  // If UVs are valid/coherent, we see structured colour; if corrupted,
  // we see random/spiky patterns.
  gs_out.vertColour = vec4(
    1.0 - abs(vs_out[i].vertUv.x - 0.5) * 2.0,
    1.0 - abs(vs_out[i].vertUv.y - 0.5) * 2.0,
    dot(vs_out[i].vertUv, vec2(0.5)),
    1.0
  );
  gs_out.vertFlags = vs_out[i].vertFlags;
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
    //_mat4 invProj = inverse(projection);
    // z0 = recoverZ(gl_in[0].gl_Position, invProj);
    // z2 = recoverZ(gl_in[2].gl_Position, invProj);
    // z4 = recoverZ(gl_in[4].gl_Position, invProj);
    // Using stock viewspaceZ when inverse not available in probe.
    z0 = vs_out[0].viewspaceZ;
    z2 = vs_out[2].viewspaceZ;
    z4 = vs_out[4].viewspaceZ;
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

  // Compute average depth from clip-space Z for cull fallback.
  float avgDepth = gl_in[0].gl_Position.z + gl_in[2].gl_Position.z + gl_in[4].gl_Position.z;
  avgDepth /= 3.0;

  emitProbe(0, avgDepth);
  emitProbe(2, avgDepth);
  emitProbe(4, avgDepth);
  EndPrimitive();
}