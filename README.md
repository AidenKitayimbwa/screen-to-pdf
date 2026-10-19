# ScreenToPdf

## What this app does

ScreenToPdf turns a video of a computer screen into a PDF you can search.

Point your phone at a screen and record while you scroll through a document. The app picks one clean image for each page. It then builds a PDF where each page keeps the shape of the original and the words can be searched and copied.

The app runs on Android phones. All the work happens on the phone. Nothing is sent to the internet.

## How to get the app on your phone

The app is not in the Play Store. GitHub builds the install file for you. The install file is called an APK (the Android package that holds an app).

1. Push the project to GitHub.
2. On the GitHub page for the project, select the **Actions** tab.
3. Select the run called 'Build debug APK'. Wait until it shows a green tick.
4. Scroll down to the 'Artifacts' section and download `ScreenToPdf-debug-apk`.
5. Unzip the download and copy the `.apk` file to your phone. A cable, Google Drive or WhatsApp all work.
6. On the phone, open the file. If the phone asks, allow installs from unknown sources for the app you opened it from.
7. Select **Install**, then open ScreenToPdf.

A debug APK is a test build. It is not signed for the Play Store, so the phone warns you before it installs. That is expected.

## How to record a good video

The cleaner the video, the better the PDF. Follow these points:

- hold the phone steady, or rest it on something
- fill the camera frame with the screen, and keep the whole page in view
- scroll one page at a time, and pause for about one second on each page
- avoid reflections from windows and lamps on the screen
- turn the screen brightness up

Do not scroll while the page is still blurred from the last scroll. The app only keeps frames where the screen is still.

## How the app works

The app works in 4 steps:

1. It finds the still moments in the video. These are the frames where you paused on a page.
2. It finds the page on the screen and flattens it. This fixes the angle and the rotation of the camera.
3. It cleans the image. It whitens the background and removes glare where it can.
4. It builds a searchable PDF. Each image becomes one A4 page, and on-device OCR adds a hidden text layer.

OCR means optical character recognition. It is software that reads the letters in a picture and turns them into text. The text layer is invisible, but it sits on top of the picture. This lets you search the PDF and select the words.

## Known limits

The app works best when you scroll page by page. These are the limits we know about:

- a long web page becomes several pages, and they may overlap at the edges
- the text layer comes from on-device OCR, so it may contain mistakes
- very small text, handwriting and text on dark backgrounds read less well
- the first run with the text layer needs the OCR model, which Google Play services may download in the background

If the text layer fails on a page, the app still produces the PDF. That page then has no hidden text.

## Project layout

The code sits in `app/src/main/java/com/aiden/screentopdf/`. It has 4 packages (folders of related code):

- `core`: the shared contracts. It defines what a page is and how the 3 other packages talk to each other.
- `ui`: the screens. It records the video, shows progress and lets you share the PDF.
- `processing`: the video work. It pulls frames from the video, finds the page and cleans the image.
- `pdf`: the PDF work. It fits each page image onto an A4 page, runs OCR and writes the file.

Each package has one `install()` function. The app calls these at start-up so the screens know which code to use.
