"""
Pages galerie des README (docs/screenshots/<langue>/README.md) : les captures en grand, légendées dans la langue.
La mosaïque cliquable (gallery.jpg) est rendue par tools/video (composition Gallery).

  python tools/demo/make_gallery.py
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
SHOTS = ['home', 'dashboard', 'members', 'cameras-energy', 'camera-wall', 'detection-history', 'avatar-editor', 'fullscreen-zoom']

T = {
    'en': ('📸 Screenshots', 'Demo data.', 'README.md', [
        'Home screen: M.A.C.S., time and weather', 'Dashboard: rooms, alarm, weather and cameras',
        'Members: presence, avatars and upcoming events', 'Energy and live cameras',
        'Camera wall, live depending on the power supply', 'Detection history while playing',
        'Avatar editor: cut-out and filters', 'Fullscreen zoom with the playback bar']),
    'fr': ('📸 Captures d\'écran', 'Données de démonstration.', 'README.fr.md', [
        'Accueil : M.A.C.S., l\'heure et la météo', 'Tableau de bord : pièces, alarme, météo et caméras',
        'Membres : présence, avatars et prochains événements', 'Énergie et caméras en direct',
        'Mur de caméras, en direct selon l\'alimentation', 'Historique des détections pendant une lecture',
        'Éditeur d\'avatar : détourage et filtres', 'Plein écran zoomé avec la barre de lecture']),
    'de': ('📸 Screenshots', 'Demodaten.', 'README.de.md', [
        'Startbildschirm: M.A.C.S., Uhrzeit und Wetter', 'Dashboard: Räume, Alarm, Wetter und Kameras',
        'Mitglieder: Anwesenheit, Avatare und nächste Termine', 'Energie und Live-Kameras',
        'Kamerawand, live je nach Stromversorgung', 'Erkennungsverlauf während der Wiedergabe',
        'Avatar-Editor: Freistellen und Filter', 'Vollbild-Zoom mit Wiedergabeleiste']),
    'es': ('📸 Capturas de pantalla', 'Datos de demostración.', 'README.es.md', [
        'Inicio: M.A.C.S., la hora y el tiempo', 'Panel: habitaciones, alarma, tiempo y cámaras',
        'Miembros: presencia, avatares y próximos eventos', 'Energía y cámaras en directo',
        'Muro de cámaras, en directo según la alimentación', 'Historial de detecciones durante una reproducción',
        'Editor de avatar: recorte y filtros', 'Pantalla completa con zoom y barra de reproducción']),
    'it': ('📸 Schermate', 'Dati dimostrativi.', 'README.it.md', [
        'Schermata iniziale: M.A.C.S., ora e meteo', 'Dashboard: stanze, allarme, meteo e telecamere',
        'Membri: presenza, avatar e prossimi eventi', 'Energia e telecamere in diretta',
        'Muro di telecamere, in diretta secondo l\'alimentazione', 'Cronologia dei rilevamenti durante la riproduzione',
        'Editor dell\'avatar: scontorno e filtri', 'Schermo intero con zoom e barra di riproduzione']),
    'nl': ('📸 Schermafbeeldingen', 'Demogegevens.', 'README.nl.md', [
        'Startscherm: M.A.C.S., tijd en weer', 'Dashboard: kamers, alarm, weer en camera\'s',
        'Leden: aanwezigheid, avatars en komende afspraken', 'Energie en live camera\'s',
        'Camerawand, live volgens de voeding', 'Detectiegeschiedenis tijdens het afspelen',
        'Avatar-editor: uitknippen en filters', 'Ingezoomd volledig scherm met afspeelbalk']),
    'pt': ('📸 Capturas de ecrã', 'Dados de demonstração.', 'README.pt.md', [
        'Ecrã inicial: M.A.C.S., hora e meteorologia', 'Painel: divisões, alarme, meteorologia e câmaras',
        'Membros: presença, avatares e próximos eventos', 'Energia e câmaras em direto',
        'Mural de câmaras, em direto conforme a alimentação', 'Histórico de deteções durante a reprodução',
        'Editor de avatar: recorte e filtros', 'Ecrã inteiro com zoom e barra de reprodução']),
    'pl': ('📸 Zrzuty ekranu', 'Dane demonstracyjne.', 'README.pl.md', [
        'Ekran główny: M.A.C.S., godzina i pogoda', 'Panel: pomieszczenia, alarm, pogoda i kamery',
        'Członkowie: obecność, awatary i najbliższe wydarzenia', 'Energia i kamery na żywo',
        'Ściana kamer, na żywo zależnie od zasilania', 'Historia wykryć podczas odtwarzania',
        'Edytor awatara: wycinanie i filtry', 'Pełny ekran z powiększeniem i paskiem odtwarzania']),
}

for lang, (title, demo, readme, captions) in T.items():
    out = [f'# {title}', '', f'[← README](../../../{readme}) · *{demo}*', '']
    for shot, cap in zip(SHOTS, captions):
        src = '../fullscreen-zoom.jpg' if shot == 'fullscreen-zoom' else f'{shot}.jpg'
        out += [f'### {cap}', '', f'![{cap}]({src})', '']
    path = os.path.join(ROOT, 'docs', 'screenshots', lang, 'README.md')
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
    print(path)
