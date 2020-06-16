#version 330

in vec2 outTexCoord;
in vec3 mvVertexPos;

out vec4 fragColor;

struct Fog {
	bool activ;
	vec3 color;
	float density;
};

uniform sampler2D texture_sampler;
uniform bool materialHasTexture;
uniform Fog fog;
uniform vec3 light;

vec4 ambientC;

void setupColours( bool materialHasTexture, vec2 textCoord)
{
    if (materialHasTexture)
    {
        ambientC = texture(texture_sampler, textCoord);
    }
    else
    {
        ambientC = vec4(1, 1, 1, 1);
    }
}

vec4 calcFog(vec3 pos, vec4 color, Fog fog) {
	float distance = length(pos);
	float fogFactor = 1.0 / exp((distance * fog.density) * (distance * fog.density));
	fogFactor = clamp(fogFactor, 0.0, 1.0);
	vec3 resultColor = mix(fog.color, color.xyz, fogFactor);
	return vec4(resultColor.xyz, color.w);
}

void main()
{
    setupColours(materialHasTexture, outTexCoord);
    
    fragColor = ambientC * vec4(light, 1);
    
    if (fog.activ) {
        fragColor = calcFog(mvVertexPos, fragColor, fog);
    }
}