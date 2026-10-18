#version 330 core
// PLAIN-varyings probe for RG406H (Mali-G57): identical logic to stock
// tmd.fsh, but inputs are the scalar GS outputs of plain/tmd.gsh
// (g-prefixed names). See plain/tmd.vsh header for the driver rationale.

smooth in vec2 gVertUv;
flat in vec2 gVertTpage;
flat in vec2 gVertClut;
flat in int gVertBpp;
smooth in vec4 gVertColour;
flat in int gVertFlags;

flat in int gTranslucency;

flat in float gWidthMultiplier;
flat in int gWidthMask;
flat in int gIndexShift;
flat in int gIndexMask;

smooth in float gDepth;
smooth in float gDepthOffset;

layout(std140) uniform projectionInfo {
  float znear;
  float zfar;
  float zdiffInv;
  float projectionMode;
};

layout(std140) uniform scissor {
  float scissorX;
  float scissorY;
  float scissorW;
  float scissorH;
};

uniform vec3 recolour;
uniform vec2 uvOffset;
uniform float discardTranslucency;
uniform int tmdTranslucency;
uniform sampler2D tex24;
uniform usampler2D tex15;

layout(location = 0) out vec4 outColour;

void main() {
  // Older Intel iGPUs are buggy and don't implement scissoring properly, causing the Shirley fight to lock up when
  // she transforms into another character. This is a workaround and reimplements scissoring at the shader level.
  if(gl_FragCoord.x < scissorX || gl_FragCoord.x >= scissorX + scissorW || gl_FragCoord.y < scissorY || gl_FragCoord.y >= scissorY + scissorH) {
    discard;
  }

  // Linearize depth for perspective transforms so that we can render ortho models at specific depths
  if(projectionMode == 2) {
    gl_FragDepth = (gDepth - znear) * zdiffInv + gDepthOffset;
  } else {
    gl_FragDepth = gl_FragCoord.z;
  }

  bool translucent = (gVertFlags & 0x8) != 0;
  bool textured = (gVertFlags & 0x2) != 0;
  outColour = gVertColour;

  int translucencyMode = gTranslucency + 1;
  if(translucent && !textured) {
    translucencyMode = tmdTranslucency + 1;
  }

  // Textured
  if(textured) {
    vec4 texColour;
    if(gVertBpp == 0 || gVertBpp == 1) {
      // Calculate CLUT index
      ivec2 uv = ivec2(gVertTpage.x + (gVertUv.x + uvOffset.x) * gWidthMultiplier, gVertTpage.y + gVertUv.y + uvOffset.y);
      ivec4 indexVec = ivec4(texelFetch(tex15, uv, 0));
      int p = (indexVec.r >> ((int(gVertTpage.x + gVertUv.x) & gWidthMask) << gIndexShift)) & gIndexMask;

      // Pull actual pixel colour from CLUT
      uint pixel = texelFetch(tex15, ivec2(gVertClut.x + p, gVertClut.y), 0).r;
      texColour.a = float(pixel >> 15 & 0x1fu) / 31.0;
      texColour.b = float(pixel >> 10 & 0x1fu) / 31.0;
      texColour.g = float(pixel >>  5 & 0x1fu) / 31.0;
      texColour.r = float(pixel       & 0x1fu) / 31.0;
    } else if(gVertBpp == 2) {
      ivec2 uv = ivec2(gVertTpage.x + (gVertUv.x + uvOffset.x), gVertTpage.y + gVertUv.y + uvOffset.y);
      uint pixel = texelFetch(tex15, uv, 0).r;
      texColour.a = float(pixel >> 15 & 0x1fu) / 31.0;
      texColour.b = float(pixel >> 10 & 0x1fu) / 31.0;
      texColour.g = float(pixel >>  5 & 0x1fu) / 31.0;
      texColour.r = float(pixel       & 0x1fu) / 31.0;
    } else {
      texColour = texture(tex24, gVertUv + uvOffset);
    }

    // Discard if (0, 0, 0)
    if(texColour.a == 0 && texColour.r == 0 && texColour.g == 0 && texColour.b == 0) {
      discard;
    }

    // If translucent primitive and texture pixel translucency bit is set, pixel is translucent so we defer rendering
    if(discardTranslucency == 1 && translucent && texColour.a != 0 || discardTranslucency == 2 && (!translucent || texColour.a == 0)) {
      discard;
    }

    outColour = clamp(outColour * texColour, 0.0, 1.0);
  } else {
    // Untextured translucent primitives don't have a translucency bit so we always discard during the appropriate discard modes
    if(discardTranslucency == 1 && translucent || discardTranslucency == 2 && !translucent) {
      discard;
    }
  }

  outColour.rgb *= recolour;

  if(translucent && translucencyMode == 1) { // (B+F)/2 translucency
    outColour.a = 0.5;
  } else {
    outColour.a = 1.0;
  }
}
