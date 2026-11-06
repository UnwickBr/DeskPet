#!/usr/bin/env python3
"""DeskPet para PC (Linux/KDE): um coelho andando por cima da tela.

Os quadros de animação ficam em desktop/sprites/ e são gerados a partir de uma
spritesheet com tools/extract_sprites.py.

Controles:
  - clique: o coelho se assusta (e acorda, se estiver dormindo)
  - arrastar e soltar: segura e arremessa o coelho
  - botão direito (ou ícone na bandeja): menu, tamanho e configurações
"""
import math
import os
import random
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, QSettings, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QAction, QActionGroup, QBitmap, QIcon, QImage, QPainter, QPixmap, QRegion
from PySide6.QtWidgets import (QApplication, QCheckBox, QDialog, QDialogButtonBox, QFormLayout, QHBoxLayout,
                               QLabel, QMenu, QSlider, QSpinBox, QSystemTrayIcon, QVBoxLayout, QWidget)

HERE = Path(__file__).resolve().parent
SPRITES_DIR = HERE / "sprites"
AUTOSTART_FILE = Path.home() / ".config/autostart/deskpet.desktop"
AUTOSTART_CONTENT = f"""[Desktop Entry]
Type=Application
Name=DeskPet
Comment=Coelhinho andando pela tela
Exec=python3 {Path(__file__).resolve()}
Icon=applications-games
Terminal=false
X-GNOME-Autostart-enabled=true
"""

ANIMATIONS = ["walk", "jump", "sleep", "sniff", "scared", "land"]
# para onde cada animação olha na spritesheet (1 = direita, -1 = esquerda)
FACING = {"sleep": -1}

TICK_MS = 16                 # ~60 quadros por segundo
WALK_PX_PER_SEC = 70         # velocidade de caminhada a 100%
FRAME_MS = {"walk": 75, "jump": 85, "sniff": 170, "scared": 95, "land": 110}
SLEEP_INTRO_MS = 450         # deitando e fechando os olhos
SLEEP_LOOP_MS = 800          # alterna o "zzz"
JUMP_HEIGHT = 55             # altura do pulo a 100%
JUMP_SPEED_MULT = 2.6        # anda mais rápido durante o pulo
REACT_DURATION_MS = 900
DEFAULT_SLEEP_TIMEOUT_MS = 30_000
GRAVITY_PX = 3.2
FALL_X_FRICTION = 0.985
SWAY_SPRING = 0.05
SWAY_DAMPING = 0.88
SWAY_VELOCITY_IMPULSE = 0.8
SWAY_MAX_ANGLE = 28.0
VELOCITY_SMOOTHING = 0.6
THROW_VELOCITY_SCALE = 14.0
MAX_THROW_SPEED = 40.0
CLICK_MOVE_THRESHOLD = 6
MASK_GROW_PX = 3


def now_ms() -> int:
    return int(time.monotonic() * 1000)


def autostart_enabled() -> bool:
    return AUTOSTART_FILE.exists()


def set_autostart(on: bool):
    if on:
        AUTOSTART_FILE.parent.mkdir(parents=True, exist_ok=True)
        AUTOSTART_FILE.write_text(AUTOSTART_CONTENT, encoding="utf-8")
    else:
        AUTOSTART_FILE.unlink(missing_ok=True)


def load_frames(folder: Path) -> dict[str, list[QImage]]:
    frames = {}
    for name in ANIMATIONS:
        files = sorted(folder.glob(f"{name}_*.png"), key=lambda p: int(p.stem.rsplit("_", 1)[1]))
        imgs = [QImage(str(f)) for f in files]
        if not imgs or any(i.isNull() for i in imgs):
            raise SystemExit(f"Faltam quadros '{name}_N.png' em {folder}. Rode tools/extract_sprites.py.")
        frames[name] = imgs
    return frames


class NotificationWatcher(QThread):
    """Escuta as notificações da área de trabalho (equivalente ao PetNotificationListener do Android)."""
    notified = Signal()

    def run(self):
        exe = shutil.which("dbus-monitor")
        if not exe:
            return
        self.proc = subprocess.Popen(
            [exe, "--session", "interface='org.freedesktop.Notifications',member='Notify'"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        for line in self.proc.stdout:
            if "member=Notify" in line:
                self.notified.emit()

    def stop(self):
        p = getattr(self, "proc", None)
        if p and p.poll() is None:
            p.terminate()


class SettingsDialog(QDialog):
    """Janela de configurações; tudo é aplicado na hora e salvo automaticamente."""

    def __init__(self, pet: "PetWindow"):
        super().__init__(None, Qt.WindowType.WindowStaysOnTopHint)
        self.pet = pet
        self.setWindowTitle("DeskPet — Configurações")
        self.setWindowIcon(QApplication.windowIcon())

        form = QFormLayout()
        self.size_slider, size_row = self._slider(30, 300, int(pet.scale * 100), "%")
        self.size_slider.valueChanged.connect(lambda v: pet.set_scale(v / 100))
        form.addRow("Tamanho:", size_row)

        self.speed_slider, speed_row = self._slider(25, 300, int(pet.speed * 100), "%")
        self.speed_slider.valueChanged.connect(lambda v: pet.set_speed(v / 100))
        form.addRow("Velocidade:", speed_row)

        self.sleep_spin = QSpinBox()
        self.sleep_spin.setRange(0, 3600)
        self.sleep_spin.setSuffix(" s")
        self.sleep_spin.setSpecialValueText("Nunca")
        self.sleep_spin.setValue(pet.sleep_timeout_ms // 1000)
        self.sleep_spin.valueChanged.connect(lambda v: pet.set_sleep_timeout(v * 1000))
        form.addRow("Dormir depois de:", self.sleep_spin)

        self.random_check = QCheckBox("Movimento aleatório (anda pela tela toda)")
        self.random_check.toggled.connect(pet.set_random_movement)
        self.notif_check = QCheckBox("Reagir às notificações do PC")
        self.notif_check.toggled.connect(pet.set_react_notifications)
        self.autostart_check = QCheckBox("Abrir sozinho ao ligar o PC")
        self.autostart_check.toggled.connect(set_autostart)
        self.sync()

        buttons = QDialogButtonBox()
        buttons.addButton("Restaurar padrão", QDialogButtonBox.ButtonRole.ResetRole).clicked.connect(self.reset_defaults)
        buttons.addButton("Fechar", QDialogButtonBox.ButtonRole.RejectRole).clicked.connect(self.close)

        lay = QVBoxLayout(self)
        lay.addLayout(form)
        for w in (self.random_check, self.notif_check, self.autostart_check):
            lay.addWidget(w)
        lay.addWidget(buttons)
        self.setMinimumWidth(420)

    def _slider(self, lo, hi, value, suffix):
        s = QSlider(Qt.Orientation.Horizontal)
        s.setRange(lo, hi)
        s.setValue(value)
        label = QLabel(f"{value}{suffix}")
        label.setMinimumWidth(48)
        s.valueChanged.connect(lambda v: label.setText(f"{v}{suffix}"))
        row = QWidget()
        h = QHBoxLayout(row)
        h.setContentsMargins(0, 0, 0, 0)
        h.addWidget(s)
        h.addWidget(label)
        return s, row

    def sync(self):
        """Atualiza os controles se algo mudou pelo menu."""
        for w, v in ((self.random_check, self.pet.random_mode), (self.notif_check, self.pet.react_notifications),
                     (self.autostart_check, autostart_enabled())):
            w.blockSignals(True)
            w.setChecked(v)
            w.blockSignals(False)
        self.size_slider.blockSignals(True)
        self.size_slider.setValue(int(round(self.pet.scale * 100)))
        self.size_slider.blockSignals(False)

    def reset_defaults(self):
        self.size_slider.setValue(100)
        self.speed_slider.setValue(100)
        self.sleep_spin.setValue(DEFAULT_SLEEP_TIMEOUT_MS // 1000)
        self.random_check.setChecked(False)
        self.notif_check.setChecked(True)


class PetWindow(QWidget):
    def __init__(self, frames: dict[str, list[QImage]], settings: QSettings):
        super().__init__(None, Qt.WindowType.FramelessWindowHint
                         | Qt.WindowType.WindowStaysOnTopHint
                         | Qt.WindowType.Tool
                         | Qt.WindowType.X11BypassWindowManagerHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setWindowTitle("DeskPet")

        self.frames = frames
        all_imgs = [i for imgs in frames.values() for i in imgs]
        self.canvas_w = max(i.width() for i in all_imgs)
        self.canvas_h = max(i.height() for i in all_imgs)

        self.settings = settings
        self.scale = max(0.3, min(3.0, settings.value("scale", 1.0, type=float)))
        self.speed = max(0.25, min(3.0, settings.value("speed", 1.0, type=float)))
        self.sleep_timeout_ms = settings.value("sleep_timeout_ms", DEFAULT_SLEEP_TIMEOUT_MS, type=int)
        self.random_mode = settings.value("random_mode", False, type=bool)
        self.react_notifications = settings.value("react_notifications", True, type=bool)
        self.settings_dialog = None
        self.setFixedSize(self.pet_w(), self.pet_h())

        geo = self.screen_geo()
        self.px = float(geo.x() + random.randint(0, max(0, geo.width() - self.width())))
        self.base_y = float(geo.y() + geo.height() - self.height())
        self.py = self.base_y

        self.direction = 1          # a arte olha para a direita
        self.vx, self.vy = 1.0, 0.0  # direção no modo aleatório
        self.redirect_at = 0

        # estado
        self.state = "walk"          # walk | sniff | jump | sleep | react | held | fall | land
        self.state_since = now_ms()
        self.state_until = 0
        self.last_interaction = now_ms()
        self.next_mood_at = now_ms() + self.random_mood_delay()
        self.anim_name, self.anim_index = "walk", 0
        self.anim_clock = 0

        # arrasto / física
        self.press_global = None
        self.press_px = self.press_py = 0.0
        self.moved_since_press = False
        self.last_drag_x = self.last_drag_y = 0.0
        self.last_move_time = 0
        self.drag_vx = self.drag_vy = 0.0
        self.fall_vy = self.fall_vx = 0.0
        self.sway_angle = self.sway_velocity = 0.0
        self.jump_start_y = 0.0

        self.render_key = None
        self.rendered = QImage()
        self.scaled_cache = {}
        self.raise_counter = 0
        self.last_tick = now_ms()

        self.menu = self.build_menu()
        if self.random_mode:
            self.pick_random_direction()
        self.timer = QTimer(self)
        self.timer.setInterval(TICK_MS)
        self.timer.timeout.connect(self.tick)
        self.apply_pos()
        self.render()

    # ---------- tamanho / tela ----------
    def pet_w(self):
        return max(30, int(self.canvas_w * 1.45 * self.scale))  # sobra nas laterais para o balanço

    def pet_h(self):
        return max(30, int(self.canvas_h * 1.05 * self.scale))

    def screen_geo(self):
        scr = self.screen() if self.isVisible() else QApplication.primaryScreen()
        return scr.availableGeometry()  # não inclui a barra de tarefas: o "chão" é o topo dela

    def bounds(self):
        g = self.screen_geo()
        return g.x(), g.y(), g.x() + g.width() - self.width(), g.y() + g.height() - self.height()

    def apply_pos(self):
        self.move(int(self.px), int(self.py))

    def set_scale(self, scale):
        old_bottom = self.py + self.height()
        was_on_ground = self.base_y >= self.bounds()[3] - 1
        self.scale = scale
        self.scaled_cache.clear()
        self.settings.setValue("scale", scale)
        self.setFixedSize(self.pet_w(), self.pet_h())
        min_x, min_y, max_x, max_y = self.bounds()
        self.px = max(min_x, min(max_x, self.px))
        self.base_y = max_y if was_on_ground else max(min_y, min(max_y, old_bottom - self.height()))
        if self.state not in ("held", "fall"):
            self.py = self.base_y
        self.apply_pos()
        self.render(force=True)

    def set_speed(self, speed):
        self.speed = speed
        self.settings.setValue("speed", speed)

    def set_sleep_timeout(self, ms):
        self.sleep_timeout_ms = ms
        self.settings.setValue("sleep_timeout_ms", ms)
        self.mark_interaction()

    def set_random_movement(self, on):
        self.mark_interaction()
        self.random_mode = on
        self.settings.setValue("random_mode", on)
        if on:
            self.pick_random_direction()
        elif self.state in ("walk", "sniff", "sleep", "react"):
            self.base_y = self.py = self.bounds()[3]
            self.apply_pos()
        if self.settings_dialog:
            self.settings_dialog.sync()

    def set_react_notifications(self, on):
        self.react_notifications = on
        self.settings.setValue("react_notifications", on)
        if self.settings_dialog:
            self.settings_dialog.sync()

    # ---------- menu ----------
    def build_menu(self):
        menu = QMenu()
        settings_action = QAction("Configurações…", menu)
        f = settings_action.font()
        f.setBold(True)
        settings_action.setFont(f)
        settings_action.triggered.connect(self.open_settings)
        menu.addAction(settings_action)

        size_menu = menu.addMenu("Tamanho")
        self.size_group = QActionGroup(size_menu)
        for label, sc in (("Pequeno (50%)", 0.5), ("Médio (75%)", 0.75), ("Normal (100%)", 1.0),
                          ("Grande (150%)", 1.5), ("Gigante (200%)", 2.0)):
            a = QAction(label, size_menu, checkable=True)
            a.setData(sc)
            a.triggered.connect(lambda _=False, v=sc: self.set_scale_from_menu(v))
            self.size_group.addAction(a)
            size_menu.addAction(a)

        self.random_action = QAction("Movimento aleatório", menu, checkable=True)
        self.random_action.toggled.connect(self.set_random_movement)
        menu.addAction(self.random_action)
        self.notif_action = QAction("Reagir às notificações", menu, checkable=True)
        self.notif_action.toggled.connect(self.set_react_notifications)
        menu.addAction(self.notif_action)

        menu.addSeparator()
        self.visible_action = QAction("Esconder", menu)
        self.visible_action.triggered.connect(self.toggle_visible)
        menu.addAction(self.visible_action)
        quit_action = QAction("Sair", menu)
        quit_action.triggered.connect(QApplication.quit)
        menu.addAction(quit_action)
        menu.aboutToShow.connect(self.sync_menu)
        return menu

    def sync_menu(self):
        for a in self.size_group.actions():
            a.setChecked(abs(a.data() - self.scale) < 0.01)
        for a, v in ((self.random_action, self.random_mode), (self.notif_action, self.react_notifications)):
            a.blockSignals(True)
            a.setChecked(v)
            a.blockSignals(False)

    def set_scale_from_menu(self, scale):
        self.set_scale(scale)
        if self.settings_dialog:
            self.settings_dialog.sync()

    def open_settings(self):
        if self.settings_dialog is None:
            self.settings_dialog = SettingsDialog(self)
        self.settings_dialog.sync()
        self.settings_dialog.show()
        self.settings_dialog.raise_()
        self.settings_dialog.activateWindow()

    def toggle_visible(self):
        if self.isVisible():
            self.hide()
            self.timer.stop()
            self.visible_action.setText("Mostrar")
        else:
            self.show()
            self.mark_interaction()
            self.set_state("walk")
            self.start()
            self.visible_action.setText("Esconder")

    def on_notification(self):
        if self.isVisible() and self.react_notifications and self.state not in ("held", "fall"):
            self.react()

    # ---------- estados ----------
    def start(self):
        self.last_tick = now_ms()
        self.timer.start()

    def mark_interaction(self):
        self.last_interaction = now_ms()

    def random_mood_delay(self):
        return random.randint(6_000, 15_000)

    def set_state(self, state, duration_ms=0):
        self.state = state
        self.state_since = now_ms()
        self.state_until = self.state_since + duration_ms if duration_ms else 0
        anim = {"walk": "walk", "sniff": "sniff", "jump": "jump", "sleep": "sleep",
                "react": "scared", "held": "scared", "fall": "land", "land": "land"}[state]
        self.anim_name = anim
        self.anim_index = 1 if state == "land" else 0
        self.anim_clock = 0

    def react(self):
        self.mark_interaction()
        self.base_y = self.py if self.state != "jump" else self.jump_start_y
        self.py = self.base_y
        self.set_state("react", REACT_DURATION_MS)

    def pick_random_direction(self):
        while True:
            self.vx, self.vy = random.choice((-1, 0, 1)), random.choice((-1, 0, 1))
            if self.vx or self.vy:
                break
        if self.vx:
            self.direction = 1 if self.vx > 0 else -1
        self.redirect_at = now_ms() + random.randint(1_500, 4_500)

    # ---------- laço principal ----------
    def tick(self):
        now = now_ms()
        dt = min(100, now - self.last_tick)
        self.last_tick = now
        self.raise_counter += 1
        if self.raise_counter >= 120:
            self.raise_counter = 0
            self.raise_()

        st = self.state
        if st == "held":
            self.advance_anim(dt, loop=True)
        elif st == "fall":
            self.fall_step()
        elif st == "land":
            if self.advance_anim(dt, loop=False):
                self.set_state("walk")
                self.next_mood_at = now + self.random_mood_delay()
        elif st == "sleep":
            self.sleep_anim(now)
        elif st == "react":
            self.advance_anim(dt, loop=True)
            if now >= self.state_until:
                self.set_state("walk")
        else:
            if self.sleep_timeout_ms > 0 and now - self.last_interaction >= self.sleep_timeout_ms \
                    and st != "jump":
                self.py = self.base_y
                self.set_state("sleep")
            elif st == "sniff":
                self.advance_anim(dt, loop=True)
                if now >= self.state_until:
                    self.set_state("walk")
                    self.next_mood_at = now + self.random_mood_delay()
            elif st == "jump":
                self.jump_step(dt, now)
            else:  # walk
                if now >= self.next_mood_at:
                    if random.random() < 0.5:
                        self.set_state("sniff", random.randint(1_500, 2_800))
                    else:
                        self.jump_start_y = self.base_y
                        self.set_state("jump", len(self.frames["jump"]) * FRAME_MS["jump"])
                        self.next_mood_at = now + random.randint(3_000, 9_000)
                else:
                    self.walk_step(dt, now)
                    self.advance_anim(dt, loop=True, speed=self.speed)

        self.sway_step()
        self.apply_pos()
        self.render()

    def advance_anim(self, dt, loop, speed=1.0):
        """Avança a animação atual. Retorna True quando uma animação sem loop termina."""
        frames = self.frames[self.anim_name]
        self.anim_clock += dt * speed
        per = FRAME_MS.get(self.anim_name, 120)
        while self.anim_clock >= per:
            self.anim_clock -= per
            if self.anim_index + 1 < len(frames):
                self.anim_index += 1
            elif loop:
                self.anim_index = 0
            else:
                return True
        return False

    def sleep_anim(self, now):
        n = len(self.frames["sleep"])
        t = now - self.state_since
        intro = (n - 1) * SLEEP_INTRO_MS
        if t < intro:
            self.anim_index = t // SLEEP_INTRO_MS
        else:  # alterna os dois últimos quadros (o "zzz" pisca)
            self.anim_index = n - 1 if ((t - intro) // SLEEP_LOOP_MS) % 2 == 0 else n - 2

    def move_speed(self, dt):
        return WALK_PX_PER_SEC * self.speed * self.scale * dt / 1000

    def walk_step(self, dt, now):
        step = self.move_speed(dt)
        min_x, min_y, max_x, max_y = self.bounds()
        if self.random_mode:
            norm = math.hypot(self.vx, self.vy) or 1
            self.px += self.vx / norm * step
            self.base_y += self.vy / norm * step
            bounced = False
            if self.px <= min_x or self.px >= max_x:
                self.vx = -self.vx
                bounced = True
            if self.base_y <= min_y or self.base_y >= max_y:
                self.vy = -self.vy
                bounced = True
            self.px = max(min_x, min(max_x, self.px))
            self.base_y = max(min_y, min(max_y, self.base_y))
            if self.vx:
                self.direction = 1 if self.vx > 0 else -1
            if not bounced and now >= self.redirect_at:
                self.pick_random_direction()
        else:
            self.base_y = max_y
            self.px += self.direction * step
            if self.px <= min_x:
                self.px, self.direction = min_x, 1
            elif self.px >= max_x:
                self.px, self.direction = max_x, -1
        self.py = self.base_y

    def jump_step(self, dt, now):
        total = self.state_until - self.state_since
        t = min(1.0, (now - self.state_since) / total)
        self.anim_index = min(len(self.frames["jump"]) - 1, int(t * len(self.frames["jump"])))
        min_x, min_y, max_x, _ = self.bounds()
        self.px += self.direction * self.move_speed(dt) * JUMP_SPEED_MULT
        if self.px <= min_x:
            self.px, self.direction = min_x, 1
        elif self.px >= max_x:
            self.px, self.direction = max_x, -1
        self.py = max(min_y, self.jump_start_y - math.sin(math.pi * t) * JUMP_HEIGHT * self.scale)
        if t >= 1.0:
            self.py = self.base_y = self.jump_start_y
            self.set_state("walk")

    def fall_step(self):
        min_x, min_y, max_x, ground_y = self.bounds()
        self.fall_vy += GRAVITY_PX
        self.py += self.fall_vy
        if self.py <= min_y:
            self.py, self.fall_vy = min_y, 0.0
        self.px += self.fall_vx
        if self.px <= min_x or self.px >= max_x:
            self.px = max(min_x, min(max_x, self.px))
            self.fall_vx = 0.0
        self.fall_vx *= FALL_X_FRICTION
        if abs(self.fall_vx) > 0.5:
            self.direction = 1 if self.fall_vx > 0 else -1
        self.anim_index = 0
        if self.py >= ground_y:
            self.py = self.base_y = ground_y
            self.fall_vx = self.fall_vy = 0.0
            self.set_state("land")

    def sway_step(self):
        if self.state != "held" and abs(self.sway_angle) < 0.05 and abs(self.sway_velocity) < 0.05:
            self.sway_angle = self.sway_velocity = 0.0
            return
        self.sway_velocity += -SWAY_SPRING * self.sway_angle
        self.sway_velocity *= SWAY_DAMPING
        self.sway_angle = max(-SWAY_MAX_ANGLE, min(SWAY_MAX_ANGLE, self.sway_angle + self.sway_velocity))

    # ---------- mouse ----------
    def mousePressEvent(self, e):
        if e.button() == Qt.MouseButton.RightButton:
            self.menu.popup(e.globalPosition().toPoint())
            return
        if e.button() != Qt.MouseButton.LeftButton:
            return
        self.mark_interaction()
        if self.state == "jump":
            self.py = self.base_y = self.jump_start_y
        self.set_state("held")
        self.press_global = e.globalPosition()
        self.press_px, self.press_py = self.px, self.py
        self.moved_since_press = False
        self.last_drag_x, self.last_drag_y = self.px, self.py
        self.last_move_time = now_ms()
        self.drag_vx = self.drag_vy = 0.0

    def mouseMoveEvent(self, e):
        if self.state != "held" or self.press_global is None:
            return
        min_x, min_y, max_x, max_y = self.bounds()
        d = e.globalPosition() - self.press_global
        if abs(d.x()) + abs(d.y()) > CLICK_MOVE_THRESHOLD:
            self.moved_since_press = True
        self.px = max(min_x, min(max_x, self.press_px + d.x()))
        self.py = max(min_y, min(max_y, self.press_py + d.y()))

        now = now_ms()
        dt = float(max(1, now - self.last_move_time))
        ivx = (self.px - self.last_drag_x) / dt
        ivy = (self.py - self.last_drag_y) / dt
        self.drag_vx = self.drag_vx * VELOCITY_SMOOTHING + ivx * (1 - VELOCITY_SMOOTHING)
        self.drag_vy = self.drag_vy * VELOCITY_SMOOTHING + ivy * (1 - VELOCITY_SMOOTHING)
        self.last_move_time = now
        self.last_drag_x, self.last_drag_y = self.px, self.py
        self.sway_velocity += self.drag_vx * SWAY_VELOCITY_IMPULSE
        self.apply_pos()

    def mouseReleaseEvent(self, e):
        if e.button() != Qt.MouseButton.LeftButton or self.state != "held":
            return
        self.mark_interaction()
        self.press_global = None
        ground_y = self.bounds()[3]
        if self.py < ground_y - 1:
            clamp = lambda v: max(-MAX_THROW_SPEED, min(MAX_THROW_SPEED, v))
            self.fall_vy = clamp(self.drag_vy * THROW_VELOCITY_SCALE)
            self.fall_vx = clamp(self.drag_vx * THROW_VELOCITY_SCALE)
            self.set_state("fall")
        else:
            self.react()

    # ---------- desenho ----------
    def current_frame(self) -> QImage:
        """Quadro atual já reduzido/ampliado com filtro de alta qualidade (fica em cache)."""
        frames = self.frames[self.anim_name]
        idx = min(self.anim_index, len(frames) - 1)
        key = (self.anim_name, idx, self.scale)
        img = self.scaled_cache.get(key)
        if img is None:
            src = frames[idx]
            w = max(1, round(src.width() * self.scale))
            h = max(1, round(src.height() * self.scale))
            img = src.convertToFormat(QImage.Format.Format_ARGB32_Premultiplied).scaled(
                w, h, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
            self.scaled_cache[key] = img
        return img

    def render(self, force=False):
        key = (self.anim_name, self.anim_index, self.direction, round(self.sway_angle, 1), self.width(), self.height())
        if key == self.render_key and not force:
            return
        self.render_key = key
        w, h = self.width(), self.height()
        img = QImage(w, h, QImage.Format.Format_ARGB32_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        frame = self.current_frame()

        p = QPainter(img)
        rotating = abs(self.sway_angle) >= 0.05
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, rotating)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, rotating)
        if rotating:
            pivot = QPointF(w / 2, h - self.canvas_h * self.scale * 0.9)  # perto da cabeça: balança como se fosse segurado
            p.translate(pivot)
            p.rotate(self.sway_angle)
            p.translate(-pivot)
        # posição inteira: sem rotação o quadro é copiado pixel a pixel, sem reamostrar
        p.translate(w // 2, h)
        if self.direction * FACING.get(self.anim_name, 1) < 0:
            p.scale(-1, 1)
        p.drawImage(-(frame.width() // 2), -frame.height(), frame)
        p.end()

        self.rendered = img
        # só a área do coelho recebe cliques; o resto deixa o clique passar.
        # A área é alargada alguns pixels para não cortar a borda suave do desenho.
        alpha = QRegion(QBitmap.fromImage(img.createAlphaMask(Qt.ImageConversionFlag.ThresholdAlphaDither)))
        if not alpha.isEmpty():
            r = MASK_GROW_PX
            region = QRegion(alpha)
            for dx, dy in ((-r, 0), (r, 0), (0, -r), (0, r), (-r, -r), (r, r), (-r, r), (r, -r)):
                region = region.united(alpha.translated(dx, dy))
            self.setMask(region)
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.setCompositionMode(QPainter.CompositionMode.CompositionMode_Source)
        p.drawImage(0, 0, self.rendered)
        p.end()


def main():
    # No Wayland um app não pode posicionar a própria janela; via XWayland pode.
    os.environ.setdefault("QT_QPA_PLATFORM", "xcb")
    app = QApplication(sys.argv)
    app.setApplicationName("DeskPet")
    app.setDesktopFileName("deskpet")
    app.setQuitOnLastWindowClosed(False)
    signal.signal(signal.SIGINT, signal.SIG_DFL)

    settings = QSettings("DeskPet", "DeskPet")
    pet = PetWindow(load_frames(SPRITES_DIR), settings)
    icon = QIcon(QPixmap.fromImage(pet.frames["walk"][0]))
    app.setWindowIcon(icon)

    tray = QSystemTrayIcon(icon)
    tray.setToolTip("DeskPet")
    tray.setContextMenu(pet.menu)
    tray.activated.connect(lambda r: pet.toggle_visible() if r == QSystemTrayIcon.ActivationReason.Trigger else None)
    tray.show()

    watcher = NotificationWatcher()
    watcher.notified.connect(pet.on_notification)
    watcher.start()
    app.aboutToQuit.connect(watcher.stop)

    pet.show()
    pet.start()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
