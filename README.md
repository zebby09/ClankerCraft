# ClankerCraft

A Minecraft Fabric mod that brings AI-powered mobs and creative tools to your world. Chat with friendly companions, generate custom paintings, and create music—all powered by Google's AI services.

---

## Components

### The Clanker Mob
A friendly companion mob that players can talk to. Based on the Copper Golem with all hostile behaviors removed. Spawn using the Clanker spawn egg.

### Chat System
Start conversations by typing `@clanker` in chat. The mob responds using AI and remembers your conversation. End with `@byebye`. Each player gets their own conversation session, so multiple players can chat with different Clankers at once.

### AI Conversations
Powered by Google's Gemini language model. Messages are sent to the Gemini API with conversation history, and the model generates natural responses. Customize the Clanker's personality using text files—choose from Excited, Grumpy, or Robotic, or create your own.

### Text-to-Speech
Clanker's responses are spoken aloud using Google Cloud Text-to-Speech with Chirp 3 HD voices. Audio plays positionally in 3D space, so you hear the voice coming from the Clanker's location.

### Image Generation
Create custom paintings by typing `@makepainting <prompt>` during a conversation. Uses Vertex AI's Imagen model to generate images from your description. The image becomes a painting texture in your world.

### Music Generation
Generate music discs by typing `@makemusic <prompt>`. Uses Vertex AI's Lyria 2 model to create music based on your description. The audio is transcoded to OGG format (requires FFmpeg) and saved as a playable music disc.

---

## AI and Cloud Services

ClankerCraft uses the following Google AI services:

- **Gemini** (Google AI Studio) — Powers natural language conversations
- **Google Cloud Text-to-Speech** — Converts text to spoken audio with Chirp 3 HD voices
- **Vertex AI Imagen** — Generates images for custom paintings
- **Vertex AI Lyria 2** — Creates music for custom discs

You'll need API keys for these services. All keys are configured in a single properties file.

---

## Setup Guide

### Prerequisites
- Java 21
- Minecraft with Fabric Loader and Fabric API installed
- FFmpeg installed and available in your system PATH (required for music generation)

### Step 1: Create Your Config File
1. Find the sample config file: `clankercraft-llm.sample.properties` in this repository
2. Copy it to your Minecraft config directory: `<minecraft>/config/clankercraft-llm.properties`
3. Open the file in a text editor

### Step 2: Get API Keys

**For Chat**
1. Go to [Google AI Studio](https://aistudio.google.com/)
2. Create an API key
3. Add it to your config file:
   ```
   GOOGLE_AI_STUDIO_API_KEY=your_key_here
   ```

**For TTS**
1. Enable the Google Cloud Text-to-Speech API in your Google Cloud Console
2. Create an API key
3. Add it to your config file:
   ```
   GOOGLE_TTS_API_KEY=your_key_here
   ```

**For Images and Music**
1. Create a Google Cloud project
2. Enable Vertex AI API
3. Create a service account and download the JSON credentials file
4. Add to your config file:
   ```
   GOOGLE_APPLICATION_CREDENTIALS=/path/to/your/credentials.json
   GOOGLE_CLOUD_PROJECT_ID=your-project-id
   GCP_LOCATION=us-central1
   ```

### Step 3: Launch Minecraft
1. Start Minecraft with the ClankerCraft mod installed
2. Check the console for confirmation messages about enabled features

### Step 4: Test the Features

**Test Chat:**
- Give yourself a Clanker spawn egg: `/give @s clankercraft:clanker_spawn_egg`
- Spawn a Clanker
- Type `@clanker hello` in chat
- You should see a response

**Test TTS:**
- If configured, you'll hear the Clanker speak its responses

**Test Paintings:**
- Start a conversation with `@clanker`
- Type `@makepainting a sunset over mountains`
- Wait for the success message
- Press `F3 + T` to reload textures
- Place a painting to see your generated image

**Test Music:**
- Start a conversation with `@clanker`
- Type `@makemusic upbeat electronic dance`
- Wait for the success message
- Pick up the generated disc
- Place it in a jukebox to play
