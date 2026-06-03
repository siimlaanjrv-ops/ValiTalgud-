<template>
  <div class="chatbot">
    <!-- Vestlusaken -->
    <transition name="chat-panel">
      <div v-if="isOpen" class="chat-window" role="dialog" aria-label="AI vestlusrobot">
        <div class="chat-header">
          <span class="chat-title">Pam</span>
          <button class="chat-close" aria-label="Sulge vestlus" @click="close">×</button>
        </div>

        <div ref="messages" class="chat-messages">
          <div
            v-for="(message, index) in messages"
            :key="index"
            class="chat-bubble"
            :class="bubbleClass(message)"
          >
            <!-- Pam kirjutab... (streaming, esmane ooteaeg) -->
            <template v-if="message.isStreaming && !message.content">
              <span class="typing-label">Pam kirjutab</span>
              <span class="typing-dots">
                <span></span><span></span><span></span>
              </span>
            </template>
            <!-- Bot-vastus markdown-iga -->
            <span v-else-if="message.role === 'assistant'" class="md-content" v-html="renderMarkdown(message.content)"></span>
            <!-- Kasutaja sõnum (plain text) -->
            <template v-else>{{ message.content }}</template>
          </div>

          <!-- Quick reply chips — näidatakse ainult tervituse järel -->
          <div v-if="showQuickReplies" class="quick-replies">
            <button
              v-for="chip in quickReplies"
              :key="chip"
              class="quick-chip"
              @click="useQuickReply(chip)"
            >
              {{ chip }}
            </button>
          </div>
        </div>

        <form class="chat-input" @submit.prevent="sendMessage">
          <input
            ref="field"
            v-model="draft"
            type="text"
            class="chat-field"
            placeholder="Küsi midagi…"
            :disabled="isLoading"
            aria-label="Sinu sõnum"
          />
          <button type="submit" class="chat-send" :disabled="isLoading || !draft.trim()">
            Saada
          </button>
        </form>
      </div>
    </transition>

    <!-- Avamise nupp -->
    <button
      class="chat-toggle"
      :class="{ 'is-open': isOpen }"
      :aria-label="isOpen ? 'Sulge vestlus' : 'Ava AI vestlus'"
      @click="toggle"
    >
      {{ isOpen ? '×' : '💬' }}
    </button>
  </div>
</template>

<script>
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.use({ breaks: true, gfm: true })

const MAX_HISTORY = 10

function getGreeting() {
  const h = new Date().getHours()
  if (h < 12) return 'Tere hommikust!'
  if (h < 18) return 'Tere päevast!'
  return 'Tere õhtust!'
}

export default {
  name: 'ChatbotWidget',
  data() {
    return {
      isOpen: false,
      isLoading: false,
      draft: '',
      messages: [
        {
          role: 'assistant',
          content: `${getGreeting()} Olen Pam 👋 Küsi minult Valitalgude kohta — sündmused, registreerumine, e-pood.`,
        },
      ],
      quickReplies: ['Kuidas registreeruda?', 'Tallinna talgud', 'E-pood', 'Loo sündmus'],
    }
  },
  computed: {
    showQuickReplies() {
      return this.messages.length === 1 && !this.isLoading
    },
  },
  methods: {
    toggle() {
      this.isOpen = !this.isOpen
      if (this.isOpen) {
        this.scrollToBottom()
        this.focusField()
      }
    },
    close() {
      this.isOpen = false
    },
    useQuickReply(question) {
      this.draft = question
      this.sendMessage()
    },
    async sendMessage() {
      const text = this.draft.trim()
      if (!text || this.isLoading) return

      const history = this.messages.slice(-MAX_HISTORY).map((m) => ({
        role: m.role,
        content: m.content,
      }))

      this.messages.push({ role: 'user', content: text })
      this.draft = ''
      this.isLoading = true
      this.scrollToBottom()

      this.messages.push({ role: 'assistant', content: '', isStreaming: true })
      const botIdx = this.messages.length - 1

      try {
        const response = await fetch('/api/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: text, history }),
        })

        if (!response.ok) {
          let errorMsg = 'Vabandust, midagi läks valesti. Proovi uuesti.'
          if (response.status === 429) {
            try {
              const body = await response.json()
              errorMsg = body.message || 'Oota hetk enne järgmist sõnumit 🙂'
            } catch (_parseErr) {
              errorMsg = 'Oota hetk enne järgmist sõnumit 🙂'
            }
          }
          this.messages[botIdx].isError = true
          this.messages[botIdx].content = errorMsg
          return
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const events = buffer.split('\n\n')
          buffer = events.pop() || ''

          for (const event of events) {
            let isError = false
            const dataLines = []

            for (const line of event.split('\n')) {
              if (line.startsWith('event:') && line.includes('error')) {
                isError = true
              } else if (line.startsWith('data:')) {
                dataLines.push(line.slice(5))
              }
            }

            if (isError) {
              this.messages[botIdx].isError = true
              this.messages[botIdx].content =
                dataLines.join('\n').trim() || 'Vabandust, midagi läks valesti. Proovi uuesti.'
              break
            }

            const rawData = dataLines.join('\n')
            if (!rawData.trim() || rawData.trim() === '[DONE]') continue

            try {
              const token = JSON.parse(rawData)
              if (token) {
                this.messages[botIdx].content += token
                this.scrollToBottom()
              }
            } catch (_e) {
              // ignore malformed chunk
            }
          }
        }
      } catch (_e) {
        this.messages[botIdx].isError = true
        this.messages[botIdx].content = 'Vabandust, midagi läks valesti. Proovi uuesti.'
      } finally {
        this.messages[botIdx].isStreaming = false
        this.isLoading = false
        this.scrollToBottom()
        this.focusField()
      }
    },
    renderMarkdown(content) {
      if (!content) return ''
      return DOMPurify.sanitize(marked.parse(content))
    },
    bubbleClass(message) {
      if (message.role === 'user') return 'chat-bubble-user'
      return message.isError ? 'chat-bubble-error' : 'chat-bubble-bot'
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.messages
        if (container) container.scrollTop = container.scrollHeight
      })
    },
    focusField() {
      this.$nextTick(() => {
        if (this.$refs.field) this.$refs.field.focus()
      })
    },
  },
}
</script>

<style scoped>
.chatbot {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1050;
}

/* Avamise nupp */
.chat-toggle {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: var(--nb-border);
  background: var(--nb-pink);
  color: #fff;
  font-size: 1.6rem;
  line-height: 1;
  cursor: pointer;
  box-shadow: var(--nb-shadow);
  transition: transform 0.12s ease, box-shadow 0.12s ease;
}

.chat-toggle:hover {
  transform: translate(-2px, -2px);
  box-shadow: var(--nb-shadow-lg);
}

.chat-toggle.is-open {
  background: var(--nb-black);
}

/* Vestlusaken */
.chat-window {
  position: absolute;
  right: 0;
  bottom: 76px;
  width: 360px;
  max-width: calc(100vw - 48px);
  height: min(70vh, 540px);
  display: flex;
  flex-direction: column;
  background: var(--nb-white);
  border: var(--nb-border);
  box-shadow: var(--nb-shadow-lg);
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.6rem 0.9rem;
  background: var(--nb-black);
  border-bottom: var(--nb-border);
  flex-shrink: 0;
}

.chat-title {
  color: var(--nb-yellow);
  font-family: 'Archivo Black', sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.chat-close {
  background: none;
  border: none;
  color: #fff;
  font-size: 1.5rem;
  line-height: 1;
  cursor: pointer;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 0.9rem;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.chat-bubble {
  max-width: 85%;
  padding: 0.55rem 0.75rem;
  border: 2px solid var(--nb-black);
  font-size: 0.92rem;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-bubble-bot {
  align-self: flex-start;
  background: #f3f3f3;
}

.chat-bubble-user {
  align-self: flex-end;
  background: var(--nb-blue);
  color: #fff;
}

.chat-bubble-error {
  align-self: flex-start;
  background: var(--nb-pink);
  color: #fff;
}

/* Markdown sisu bot-mullis */
.chat-bubble-bot .md-content {
  white-space: normal;
}

.chat-bubble-bot .md-content :deep(p) {
  margin: 0 0 0.4rem;
}

.chat-bubble-bot .md-content :deep(p:last-child) {
  margin: 0;
}

.chat-bubble-bot .md-content :deep(ul),
.chat-bubble-bot .md-content :deep(ol) {
  margin: 0.2rem 0;
  padding-left: 1.2rem;
}

.chat-bubble-bot .md-content :deep(li) {
  margin: 0.1rem 0;
}

.chat-bubble-bot .md-content :deep(strong) {
  font-weight: 700;
}

/* "Pam kirjutab..." indikaator */
.typing-label {
  font-size: 0.92rem;
}

.typing-dots {
  display: inline-flex;
  gap: 3px;
  margin-left: 2px;
  vertical-align: middle;
}

.typing-dots span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--nb-black);
  display: inline-block;
  animation: chat-blink 1.2s infinite ease-in-out both;
}

.typing-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes chat-blink {
  0%,
  80%,
  100% {
    opacity: 0.2;
  }
  40% {
    opacity: 1;
  }
}

/* Quick reply chips */
.quick-replies {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-top: 0.2rem;
}

.quick-chip {
  padding: 0.3rem 0.65rem;
  border: 2px solid var(--nb-black);
  background: var(--nb-white);
  font-size: 0.82rem;
  cursor: pointer;
  transition: transform 0.1s ease, box-shadow 0.1s ease, background 0.1s ease;
}

.quick-chip:hover {
  background: var(--nb-yellow);
  transform: translate(-1px, -1px);
  box-shadow: 2px 2px 0 var(--nb-black);
}

/* Sisestusväli */
.chat-input {
  display: flex;
  gap: 0.5rem;
  padding: 0.7rem;
  border-top: var(--nb-border);
  background: var(--nb-white);
  flex-shrink: 0;
}

.chat-field {
  flex: 1;
  min-width: 0;
  padding: 0.5rem 0.6rem;
  border: 2px solid var(--nb-black);
  font-size: 0.92rem;
}

.chat-field:focus {
  outline: none;
  box-shadow: 2px 2px 0 var(--nb-black);
}

.chat-send {
  padding: 0.5rem 0.9rem;
  border: 2px solid var(--nb-black);
  background: var(--nb-yellow);
  color: var(--nb-black);
  font-weight: 700;
  cursor: pointer;
}

.chat-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Sisse-/väljalibisemine paremalt */
.chat-panel-enter-active,
.chat-panel-leave-active {
  transition: transform 0.18s ease, opacity 0.18s ease;
}

.chat-panel-enter-from,
.chat-panel-leave-to {
  transform: translateX(20px);
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .chat-toggle,
  .chat-panel-enter-active,
  .chat-panel-leave-active {
    transition: none;
  }

  .typing-dots span {
    animation: none;
  }
}
</style>