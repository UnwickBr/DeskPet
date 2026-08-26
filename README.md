# DeskPet

Um bichinho pixel art (um coelho) que fica andando de um lado pro outro por cima
dos seus outros apps no Android, tipo os pets de desktop antigos do Windows —
só que no celular.

## Como funciona

- `MainActivity` mostra três botões: pedir a permissão de sobreposição, iniciar
  e parar o bichinho.
- `PetOverlayService` é um foreground service que adiciona uma `PetView` como
  janela overlay (`TYPE_APPLICATION_OVERLAY`) por cima de tudo.
- `PetView` desenha o coelho em pixel art (bitmap gerado a partir de uma grade
  de caracteres), faz ele andar de um lado ao outro da tela com uma animação
  de pulo, e permite arrastar o bichinho pela tela com o dedo.

## Rodando o projeto

Pré-requisitos: Android SDK (`minSdk` 26 / `targetSdk` 34), um dispositivo ou
emulador com Android 8+.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Depois, no app: toque em **Permitir sobreposição**, conceda a permissão e
toque em **Iniciar bichinho**. Toque em **Parar** para encerrar o serviço.

## Editando o sprite

O coelho é definido em [`PetView.kt`](app/src/main/java/com/deskpet/walker/PetView.kt)
como uma grade de caracteres (`SPRITE`), onde cada letra vira um pixel colorido:

```kotlin
'.' -> transparente
'K' -> contorno preto
'F' -> pelagem
'E' -> orelha interna
'R' -> detalhe rosa
```

Pra desenhar ou ajustar o sprite sem editar string por string na mão, tem um
editor visual em [`tools/sprite-studio.html`](tools/sprite-studio.html):
abra o arquivo em qualquer navegador, pinte a grade de pixels, ajuste as cores
de pelagem/orelha e clique em **Copiar código** para pegar o array Kotlin já
pronto no formato que o `PetView.kt` espera.
