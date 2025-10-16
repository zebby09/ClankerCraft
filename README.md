# **ClankerCraft**
Welcome to ClankerCraft, the Minecraft mod that cranks your game up with AI magic! Cooler mobs, better immersion, and a few wild surprises make your world feel way more alive. Made with love and passion by some truly amazing authors who think Minecraft could use a little more... FUN! Dive in and see what magic the AI has in store!


This project is a Minecraft mod built using the Fabric toolchain. The build.gradle and gradle.properties files manage the project's dependencies and build configurations. The mod is written in Java 21 and uses Gradle for building.
The project is structured as a standard Fabric mod with client and server-side initializers. The main entry point for the mod is the ClankerCraft class, which initializes all the different components of the mod.

## **Subcomponents**

### 1) **New Mob: The Clanker**
ClankerCraft introduces a new mob to the game called '_Clanker_'. This mob is a friendly and interactive companion that players can converse with. The _Clanker_ entity is based on the vanilla Illusioner mob but with its hostile AI removed. Instead, it has custom AI that allows it to interact with players in a conversational manner.

### 2) **Conversation System**
The conversation system is the core of the ClankerCraft mod. It allows players to have natural language conversations with the _Clanker_ mob. The conversation is initiated by typing "@clanker" in the chat. Once a conversation is started, the player can chat with the mob as if it were another player. The conversation can be ended by typing "@byebye".

The conversation system is managed by the ChatInteraction class, which handles all the logic for parsing player messages, managing conversation state, and sending responses back to the player. Each player has their own conversation session with the _Clanker_ mob, which allows for multiple players to be conversing with different mobs at the same time.

### 3) **LLM Interaction**
The LLM interaction is what allows the _Clanker_ mob to have intelligent and engaging conversations with players. The mod uses the Gemini large language model from Google AI Studio to generate the mob's responses. The LLMClient class handles all the communication with the Gemini API.

When a player sends a message to the Clanker mob, the message is sent to the Gemini API along with the conversation history. The Gemini model then generates a response, which is sent back to the player as a chat message from the mob. The mod also includes a personality system that allows the mob's personality to be customized. The personality is defined in a text file and is used to steer the LLM's responses.

### 4) **TTS Implementation**
To make the conversation with the _Clanker_ mob more immersive, the mod includes a Text-to-Speech (TTS) implementation. When the mob responds to a player, its message is converted to speech and played back to the player. The TTS implementation uses the Google Cloud Text-to-Speech API to generate the audio. The audio model chosen for this is based on one of the available 'Chirp 3 HD' voices. These voices were chosen because they offer high-quality audio, low-latency streaming, and natural-sounding speech, incorporating human disfluencies, emotional range, and accurate intonation. 

The ClientTTS class handles all the communication with the TTS API and the playback of the audio. The audio is played back in-game using OpenAL, which allows for positional audio. This means that the sound of the mob's voice will come from its location in the game world.

### 5) **Image Generation**
ClankerCraft also features an image generation system that allows players to create custom paintings within the game. This feature is triggered by typing "@makepainting" in the chat, followed by a prompt. The mod then uses Vertex AI's Imagen model to generate an image based on the provided prompt.

The generated image is then saved as a PNG file and applied as a texture to a painting in the game. The ImagenClient class manages the interaction with the Imagen API, while the ChatInteraction class handles the in-game command and the process of updating the painting texture.

### 6) **Sound Generation**
In addition to the TTS implementation, the mod also includes a sound generation feature that allows the _Clanker_ mob to create music. This feature is triggered by typing "@makemusic" in the chat followed by a prompt. The mod then uses the Lyria 2 model from Vertex AI API through google cloud to generate a piece of music based on the prompt.

The generated music is then transcoded to the OGG Vorbis format and saved as a music disc in the game. The player can then play the music disc in a jukebox to listen to the generated music. The Lyria2Client and FfmpegTranscoder classes handle all the logic for generating and transcoding the music.



## **AI and Cloud Services**
The ClankerCraft mod uses a variety of AI and cloud services to power its features. These services include:
- _Gemini_: The Gemini large language model from Google AI Studio is used to generate the _Clanker_ mob's conversational responses.
- _Google Cloud Text-to-Speech_: The Google Cloud Text-to-Speech API is used to convert the mob's chat messages to speech.
- _Vertex AI_: The Vertex AI platform from Google Cloud is used to host the Lyria 2 model, which is used to generate music.

To use the AI-powered features of the mod, you will need to provide your own API keys for these services. The API keys can be configured in the clankercraft-llm.properties file in the Fabric config directory.
It is also important to note that the conversion of the .wav file created by Lyria2 to a .ogg file, which minecraft uses, requires FFMPEG to be installed on PATH. 
