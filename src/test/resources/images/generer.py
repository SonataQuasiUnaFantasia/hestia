"""Genere les images de test (Pillow). Motif : 4 quadrants de couleurs distinctes
(haut-gauche ROUGE, haut-droite VERT, bas-gauche BLEU, bas-droite BLANC) => toute
rotation / inversion est detectable en lisant les coins."""
import sys
from PIL import Image, ImageDraw, ImageOps

out = sys.argv[1]
R, G, B, W = (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255)

def quadrants(w, h, mode="RGB"):
    im = Image.new(mode, (w, h))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w // 2 - 1, h // 2 - 1], fill=R)
    d.rectangle([w // 2, 0, w - 1, h // 2 - 1], fill=G)
    d.rectangle([0, h // 2, w // 2 - 1, h - 1], fill=B)
    d.rectangle([w // 2, h // 2, w - 1, h - 1], fill=W)
    return im

# Transformation a appliquer aux pixels STOCKES pour que l'orientation EXIF n redonne le visuel.
T = Image.Transpose
INVERSE = {1: None, 2: T.FLIP_LEFT_RIGHT, 3: T.ROTATE_180, 4: T.FLIP_TOP_BOTTOM,
           5: T.TRANSPOSE, 6: T.ROTATE_90, 7: T.TRANSVERSE, 8: T.ROTATE_270}

def exif_jpeg(nom, visuel, orientation, valeur_exif=None):
    stocke = visuel if INVERSE[orientation] is None else visuel.transpose(INVERSE[orientation])
    exif = Image.Exif()
    exif[0x0112] = orientation if valeur_exif is None else valeur_exif
    stocke.save(f"{out}/{nom}", "JPEG", quality=95, subsampling=0, exif=exif.tobytes())
    return stocke.size

visuel = quadrants(96, 64)                       # visuel attendu : paysage 96x64
for o in range(1, 9):
    taille = exif_jpeg(f"exif-{o}.jpg", visuel, o)
    relu = ImageOps.exif_transpose(Image.open(f"{out}/exif-{o}.jpg")).convert("RGB")
    assert relu.size == (96, 64), (o, relu.size)
    for (x, y), c in {(10, 10): R, (85, 10): G, (10, 54): B, (85, 54): W}.items():
        p = relu.getpixel((x, y))
        assert all(abs(a - b) < 12 for a, b in zip(p, c)), (o, (x, y), p, c)
    print("exif", o, "stocke", taille, "-> visuel", relu.size)

# Grand JPEG stocke en paysage 1600x1200, orientation 6 => visuel portrait 1200x1600.
print("exif-6-grand", exif_jpeg("exif-6-grand.jpg", quadrants(1200, 1600), 6))
# Valeur d'orientation hors plage (9) : doit etre traitee comme "pas de rotation".
print("exif-invalide", exif_jpeg("exif-invalide.jpg", visuel, 1, valeur_exif=9))
# Sans aucune metadonnee EXIF.
quadrants(96, 64).save(f"{out}/sans-exif.jpg", "JPEG", quality=95, subsampling=0)

# Segment EXIF tronque / incoherent : on corrompt l'en-tete TIFF d'un fichier valide.
data = bytearray(open(f"{out}/exif-6.jpg", "rb").read())
i = data.find(b"Exif\x00\x00")
assert i > 0
data[i + 6:i + 14] = b"\xff" * 8               # ordre des octets + offset IFD illisibles
open(f"{out}/exif-corrompu.jpg", "wb").write(bytes(data))

# WebP reels : avec pertes (VP8), sans pertes avec alpha (VP8L), avec pertes + alpha (VP8X).
quadrants(1600, 900).save(f"{out}/paysage.webp", "WEBP", quality=85)
tr = quadrants(300, 200, "RGBA")
ImageDraw.Draw(tr).rectangle([0, 0, 149, 99], fill=(0, 0, 0, 0))   # quadrant haut-gauche transparent
tr.save(f"{out}/transparent-lossless.webp", "WEBP", lossless=True)
tr.save(f"{out}/transparent-lossy.webp", "WEBP", quality=85)
for n in ("paysage.webp", "transparent-lossless.webp", "transparent-lossy.webp"):
    b = open(f"{out}/{n}", "rb").read()
    print(n, len(b), b[:4], b[8:16])
