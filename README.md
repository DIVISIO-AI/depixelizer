This is an Open source implementation of "Depixelizing Pixel Art" by Johannes Kopf and Dani Lischinski (see https://johanneskopf.de/publications/pixelart/) for more information.

Differences to the paper: 
- it performs spline optimization by random perturbation instead of gradient descent
- it does not include the diffusion step as the target output format is SVG for which we found no way to add the complex color gradient created by the diffusion

*Note that this algorithm is explicitly targeted at handcrafted high contrast pixel art (think early 90s videogames) - it does not vectcorize or other illustrations well, if at all.*

It is licensed under the Apache License Version 2.0, for more details see `LICENSE.txt`

To build it, you need a JDK for JAVA 11 or higher. To build, just call the included maven wrapper: 

    mvnw clean package

To run the command line application: 

    java -jar depixelizer-1.0.0.jar FILE_NAME

To run the debug UI: 

    java -cp depixelizer-1.0.0.jar divisio.depixelizer.SwingUi

A note on Agentic Coding: The algorithm is completely hand coded, the command line application and UI were quickly thrown together using Roo Code & Claude Sonnett 4.5

