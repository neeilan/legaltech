package com.constantin.wilson.FPV_VR;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.opengl.EGL14;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import static android.content.Context.MODE_PRIVATE;

/*
 * The EGL SurfaceView class that enables Rendering to the Front Buffer.
 * More Hack than proper usage of the GLSurfaceView class (infinite Loop in Renderer.onDrawFrame usw)
 * choosing a single buffered config is hard-coded. Only works in Android 7 or higher.
 * TODO: Mutable Render Buffer should be preferred
 * MSAA on/off and the Config is handled by EGL14Config class
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class GLSurfaceViewFB extends SurfaceView implements SurfaceHolder.Callback
{
    private static Context mContext;
    private static final String		TAG						= "GLSurfaceViewEGL14";

    private final static boolean	LOG_ATTACH_DETACH		= false;
    private final static boolean	LOG_PAUSE_RESUME		= false;
    private final static boolean	LOG_SURFACE				= false;
    private final static boolean	LOG_RENDERER			= false;
    private final static boolean	LOG_EGL					= false;
    private final static boolean	LOG_THREADS				= false;

    /**
     * The renderer only renders when the surface is created, or when {@link #requestRender} is called.
     *
     * @see #setRenderMode(int)
     * @see #requestRender()
     */
    private final static int		RENDERMODE_WHEN_DIRTY	= 0;

    /**
     * The renderer is called continuously to re-render the scene.
     *
     * @see #setRenderMode(int)
     */
    private final static int		RENDERMODE_CONTINUOUSLY	= 1;

    /**
     * Check glError() after every GL call and throw an exception if glError indicates that an error has occurred. This can be used to help track down
     * which OpenGL ES call is causing an error.
     *
     * see getDebugFlags, setDebugFlags
     */
    private final static int		DEBUG_CHECK_GL_ERROR	= 1;

    /**
     * Log GL calls to the system log at "verbose" level with tag "GLSurfaceViewEGL14".
     *
     * see #getDebugFlags, setDebugFlags
     */
    private final static int		DEBUG_LOG_GL_CALLS		= 2;

    private final static boolean	LOG_THREADS_WAIT		= false;
    private final static boolean	LOG_RENDERER_DRAW_FRAME	= false;

    /**
     * Standard View constructor. In order to render something, you must call {@link #setRenderer} to register a renderer.
     *
     * @param context
     *            Context used for operations
     */
    public GLSurfaceViewFB(final Context context)
    {
        super(context);
        mContext=context;
        this.setWillNotDraw(false);

        this.init(context);
    }

    /**
     * Standard View constructor. In order to render something, you must call {@link #setRenderer} to register a renderer.
     *
     * @param context
     *            Context used for operations
     * @param attrs
     *            Attributes
     */
    public GLSurfaceViewFB(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);

        this.setWillNotDraw(false);

        this.init(context);
    }

    /**
     * Sets the surface to use the EGL_RECORDABLE_ANDROID flag
     * <p>
     * To take effect must be called before than setRenderer()
     *
     * @param recordable
     *            True to set the recordable flag
     */
    public void setRecordable(final boolean recordable)
    {
        this.mRecordable = recordable;
        Log.i(GLSurfaceViewFB.TAG, "Updated recordable flag. State: ".concat(Boolean.toString(recordable)));
    }

    /**
     * @see java.lang.Object#finalize()
     */
    @Override
    protected void finalize() throws Throwable
    {
        try
        {
            if (this.mGLThread != null)
            {
                // GLThread may still be running if this view was never attached to a window.
                this.mGLThread.requestExitAndWait();
            }
        }
        finally
        {
            super.finalize();
        }
    }

    private void init(final Context context)
    {
        // Request an 2.0 OpenGL ES compatible context
        this.setEGLContextClientVersion(2);

        if ((context.getApplicationContext().getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        {
            this.setDebugFlags(GLSurfaceViewFB.DEBUG_LOG_GL_CALLS | GLSurfaceViewFB.DEBUG_CHECK_GL_ERROR);
        }

        this.hookCallbacks();
    }

    /**
     * Install a SurfaceHolder.Callback so we get notified when the underlying surface is created and destroyed
     */
    private void hookCallbacks()
    {
        SurfaceHolder holder = this.getHolder();
        holder.addCallback(this);
    }

    /**
     * Set the debug flags to a new value. The value is constructed by OR-together zero or more of the DEBUG_CHECK_* constants. The debug flags take
     * effect whenever a surface is created. The default value is zero.
     *
     * @param debugFlags
     *            the new debug flags see DEBUG_CHECK_GL_ERROR see DEBUG_LOG_GL_CALLS
     */
    public void setDebugFlags(final int debugFlags)
    {
        this.mDebugFlags = debugFlags;
    }

    /**
     * Get the current value of the debug flags.
     *
     * @return the current value of the debug flags.
     */
    public int getDebugFlags()
    {
        return this.mDebugFlags;
    }

    /**
     * Control whether the EGL context is preserved when the GLSurfaceViewEGL14 is paused and resumed.
     * <p>
     * If set to true, then the EGL context may be preserved when the GLSurfaceViewEGL14 is paused. Whether the EGL context is actually preserved or
     * not depends upon whether the Android device that the program is running on can support an arbitrary number of EGL contexts or not. Devices that
     * can only support a limited number of EGL contexts must release the EGL context in order to allow multiple applications to share the GPU.
     * <p>
     * If set to false, the EGL context will be released when the GLSurfaceViewEGL14 is paused, and recreated when the GLSurfaceViewEGL14 is resumed.
     * <p>
     *
     * The default is false.
     *
     * @param preserveOnPause
     *            preserve the EGL context when paused
     */
    public void setPreserveEGLContextOnPause(final boolean preserveOnPause)
    {
        this.mPreserveEGLContextOnPause = preserveOnPause;
    }

    /**
     * @return true if the EGL context will be preserved when paused
     */
    public boolean getPreserveEGLContextOnPause()
    {
        return this.mPreserveEGLContextOnPause;
    }

    /**
     * Set the renderer associated with this view. Also starts the thread that will call the renderer, which in turn causes the rendering to start.
     * <p>
     * This method should be called once and only once in the life-cycle of a GLSurfaceViewEGL14. The following GLSurfaceViewEGL14 methods can only be
     * called <em>after</em> setRenderer is called:
     * <ul>
     * <li>{@link #getRenderMode()}
     * <li>{@link #onPause()}
     * <li>{@link #onResume()}
     * <li>{@link #queueEvent(Runnable)}
     * <li>{@link #requestRender()}
     * <li>{@link #setRenderMode(int)}
     * </ul>
     *
     * @param renderer
     *            the renderer to use to perform OpenGL drawing
     */
    public void setRenderer(final IRendererEGL14 renderer)
    {
        this.checkRenderThreadState();

        if (this.mEGLContextFactory == null)
        {
            this.mEGLContextFactory = new DefaultContextFactory();
        }

        if (this.mEGLWindowSurfaceFactory == null)
        {
            this.mEGLWindowSurfaceFactory = new DefaultWindowSurfaceFactory();
        }

        this.mRenderer = renderer;
        this.mGLThread = new GLThread(this.mThisWeakRef);
        this.mGLThread.start();
    }

    /**
     * Install a custom EGLContextFactory.
     * <p>
     * If this method is called, it must be called before {@link #setRenderer(IRendererEGL14)} is called.
     * <p>
     * If this method is not called, then by default a context will be created with no shared context and with a null attribute list.
     *
     * @param factory
     *            Factory context
     */
    public void setEGLContextFactory(final EGLContextFactory factory)
    {
        this.checkRenderThreadState();
        this.mEGLContextFactory = factory;
    }

    /**
     * Install a custom EGLWindowSurfaceFactory.
     * <p>
     * If this method is called, it must be called before {@link #setRenderer(IRendererEGL14)} is called.
     * <p>
     * If this method is not called, then by default a window surface will be created with a null attribute list.
     *
     * @param factory
     *            Factory context
     */
    public void setEGLWindowSurfaceFactory(final EGLWindowSurfaceFactory factory)
    {
        this.checkRenderThreadState();
        this.mEGLWindowSurfaceFactory = factory;
    }

    /**
     * Inform the default EGLContextFactory and default EGLConfigChooser which EGLContext client version to pick.
     * <p>
     * Use this method to create an OpenGL ES 2.0-compatible context. Example:
     *
     * <pre class="prettyprint">
     * public MyView(Context context)
     * {
     * 	super(context);
     * 	setEGLContextClientVersion(2); // Pick an OpenGL ES 2.0 context.
     * 	setRenderer(new MyRenderer());
     * }
     * </pre>
     * <p>
     * Note: Activities which require OpenGL ES 2.0 should indicate this by setting @lt;uses-feature android:glEsVersion="0x00020000" /> in the
     * activity's AndroidManifest.xml file.
     * <p>
     * If this method is called, it must be called before {@link #setRenderer(IRendererEGL14)} is called.
     * <p>
     *
     * @param version
     *            The EGLContext client version to choose. Use 2 for OpenGL ES 2.0
     */
    private void setEGLContextClientVersion(final int version)
    {
        this.checkRenderThreadState();
        this.mEGLContextClientVersion = version;
    }

    /**
     * Set the rendering mode. When renderMode is RENDERMODE_CONTINUOUSLY, the renderer is called repeatedly to re-render the scene. When renderMode
     * is RENDERMODE_WHEN_DIRTY, the renderer only rendered when the surface is created, or when {@link #requestRender} is called. Defaults to
     * RENDERMODE_CONTINUOUSLY.
     * <p>
     * Using RENDERMODE_WHEN_DIRTY can improve battery life and overall system performance by allowing the GPU and CPU to idle when the view does not
     * need to be updated.
     * <p>
     * This method can only be called after {@link #setRenderer(IRendererEGL14)}
     *
     * @param renderMode
     *            one of the RENDERMODE_X constants see RENDERMODE_CONTINUOUSLY see RENDERMODE_WHEN_DIRTY
     */
    public void setRenderMode(final int renderMode)
    {
        this.mGLThread.setRenderMode(renderMode);
    }

    /**
     * Get the current rendering mode. May be called from any thread. Must not be called before a renderer has been set.
     *
     * @return the current rendering mode. see RENDERMODE_CONTINUOUSLY see RENDERMODE_WHEN_DIRTY
     */
    public int getRenderMode()
    {
        return this.mGLThread.getRenderMode();
    }

    /**
     * Request that the renderer render a frame. This method is typically used when the render mode has been set to RENDERMODE_WHEN_DIRTY, so that
     * frames are only rendered on demand. May be called from any thread. Must not be called before a renderer has been set.
     */
    public void requestRender()
    {
        this.mGLThread.requestRender();
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is not normally called or subclassed by clients of GLSurfaceViewEGL14.
     */
    @Override
    public void surfaceCreated(final SurfaceHolder holder)
    {
        this.mGLThread.surfaceCreated();
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is not normally called or subclassed by clients of GLSurfaceViewEGL14.
     */
    @Override
    public void surfaceDestroyed(final SurfaceHolder holder)
    {
        // Surface will be destroyed when we return
        this.mGLThread.surfaceDestroyed();
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is not normally called or subclassed by clients of GLSurfaceViewEGL14.
     */
    @Override
    public void surfaceChanged(final SurfaceHolder holder, final int format, final int w, final int h)
    {
        this.mGLThread.onWindowResize(w, h);
    }

    /**
     * Inform the view that the activity is paused. The owner of this view must call this method when the activity is paused. Calling this method will
     * pause the rendering thread. Must not be called before a renderer has been set.
     */
    public void onPause()
    {
        if (this.mGLThread == null || this.mRenderer == null)
        {
            return;
        }

        this.mGLThread.onPause();
    }

    /**
     * Inform the view that the activity is resumed. The owner of this view must call this method when the activity is resumed. Calling this method
     * will recreate the OpenGL display and resume the rendering thread. Must not be called before a renderer has been set.
     */
    public void onResume()
    {
        if (this.mGLThread == null || this.mRenderer == null)
        {
            return;
        }

        this.hookCallbacks();
        this.mGLThread.onResume();
    }

    /**
     * Queue a runnable to be run on the GL rendering thread.
     * <p>
     * This can be used to communicate with the Renderer on the rendering thread.
     * <p>
     * Must not be called before a renderer has been set.
     *
     * @param runnable
     *            The runnable to be run on the GL rendering thread.
     */
    public void queueEvent(final Runnable runnable)
    {
        this.mGLThread.queueEvent(runnable);
    }

    /**
     * This method is used as part of the View class and is not normally called or sub-classed by clients of Control.
     */
    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();

        if (GLSurfaceViewFB.LOG_ATTACH_DETACH)
        {
            Log.d(GLSurfaceViewFB.TAG, "onAttachedToWindow reattach: ".concat(Boolean.toString(this.mDetached)));
        }

        if (this.mDetached && (this.mRenderer != null))
        {
            int renderMode = GLSurfaceViewFB.RENDERMODE_CONTINUOUSLY;

            if (this.mGLThread != null)
            {
                renderMode = this.mGLThread.getRenderMode();
            }

            this.mGLThread = new GLThread(this.mThisWeakRef);

            if (renderMode != GLSurfaceViewFB.RENDERMODE_CONTINUOUSLY)
            {
                this.mGLThread.setRenderMode(renderMode);
            }

            this.mGLThread.start();
        }

        this.mDetached = false;
    }

    /**
     * This method is used as part of the View class and is not normally called or sub-classed by clients of the Control. Must not be called before a
     * renderer has been set.
     */
    @Override
    protected void onDetachedFromWindow()
    {
        if (GLSurfaceViewFB.LOG_ATTACH_DETACH)
        {
            Log.d(GLSurfaceViewFB.TAG, "Detaching from window.");
        }

        if (this.mGLThread != null)
        {
            this.mGLThread.requestExitAndWait();
        }

        this.mDetached = true;
        super.onDetachedFromWindow();
    }

    // ----------------------------------------------------------------------

    /**
     * An interface for customizing the eglCreateContext and eglDestroyContext calls.
     * <p>
     * This interface must be implemented by clients wishing to call {@link GLSurfaceViewFB#setEGLContextFactory(EGLContextFactory)}
     */
    public interface EGLContextFactory
    {
        /**
         * @param display
         *            EGL Display
         * @param eglConfig
         *            EGL Configuration
         * @return EGL Context
         */
        android.opengl.EGLContext createContext(android.opengl.EGLDisplay display, android.opengl.EGLConfig eglConfig);

        /**
         * @param display
         *            EGL Display
         * @param context
         *            EGL Context
         */
        void destroyContext(android.opengl.EGLDisplay display, android.opengl.EGLContext context);
    }

    private class DefaultContextFactory implements EGLContextFactory
    {
        private final int	EGL_CONTEXT_CLIENT_VERSION	= 0x3098;

        public DefaultContextFactory()
        {
            // Empty
        }

        @Override
        public android.opengl.EGLContext createContext(final android.opengl.EGLDisplay display, final android.opengl.EGLConfig config)
        {
            final int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;
            final int[] attrib_list = { this.EGL_CONTEXT_CLIENT_VERSION, GLSurfaceViewFB.this.mEGLContextClientVersion,
                    //EGL14.EGL_SURFACE_TYPE,QCOMHelper.EGL_MUTABLE_RENDER_BUFFER_BIT_KHR,
                    //EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                    0x3100,EGL_CONTEXT_PRIORITY_HIGH_IMG, //Highest priority Context; at least this one doesn't crash, though it is not clear
                    //if it actually sets the priority as high
                    EGL14.EGL_NONE };

            return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, GLSurfaceViewFB.this.mEGLContextClientVersion != 0 ? attrib_list : null, 0);
        }

        @Override
        public void destroyContext(final android.opengl.EGLDisplay display, final android.opengl.EGLContext context)
        {
            if (EGL14.eglDestroyContext(display, context) == false)
            {
                Log.e(GLSurfaceViewFB.TAG.concat(".DefaultContextFactory"), "display:" + display + " context: " + context);

                if (GLSurfaceViewFB.LOG_THREADS)
                {
                    Log.i(GLSurfaceViewFB.TAG.concat(".DefaultContextFactory"), "tid=" + Long.toString(Thread.currentThread().getId()));
                }

                EglHelper.throwEglException("eglDestroyContex", EGL14.eglGetError());
            }
        }
    }

    /**
     * An interface for customizing the eglCreateWindowSurface and eglDestroySurface calls.
     * <p>
     * This interface must be implemented by clients wishing to call {@link GLSurfaceViewFB#setEGLWindowSurfaceFactory(EGLWindowSurfaceFactory)}
     */
    public interface EGLWindowSurfaceFactory
    {
        /**
         * @param display
         *            EGL Display
         * @param config
         *            EGL Configuration
         * @param nativeWindow
         *            Native window
         * @return EGL Context or null if the surface cannot be constructed
         */
        android.opengl.EGLSurface createWindowSurface(android.opengl.EGLDisplay display, android.opengl.EGLConfig config, Object nativeWindow);

        /**
         * @param display
         *            EGL Display
         * @param surface
         *            Surface to be destroyed
         */
        void destroySurface(android.opengl.EGLDisplay display, android.opengl.EGLSurface surface);
    }

    private static class DefaultWindowSurfaceFactory implements EGLWindowSurfaceFactory
    {
        public DefaultWindowSurfaceFactory()
        {
            // Empty
        }

        @Override
        public android.opengl.EGLSurface createWindowSurface(final android.opengl.EGLDisplay display, final android.opengl.EGLConfig config, final Object nativeWindow)
        {
            android.opengl.EGLSurface result = null;

            try
            {
                //final int[] surfaceAttribs = { EGL14.EGL_NONE };
                final int[] surfaceAttribs = {
                        //EGL10.EGL_WIDTH, 16,
                        //EGL10.EGL_HEIGHT, 16,
                        //EGL10.EGL_SURFACE_TYPE,QCOMHelper.EGL_MUTABLE_RENDER_BUFFER_BIT_KHR,
                        //QCOMHelper.EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL14.EGL_FALSE,
                        EGL14.EGL_RENDER_BUFFER,EGL14.EGL_SINGLE_BUFFER,
                        EGL14.EGL_NONE
                };

                result = EGL14.eglCreateWindowSurface(display, config, nativeWindow, surfaceAttribs, 0);
            }
            catch (Throwable ex)
            {
                // This exception indicates that the surface flinger surface
                // is not valid. This can happen if the surface flinger surface has
                // been torn down, but the application has not yet been
                // notified via SurfaceHolder.Callback.surfaceDestroyed.
                // In theory the application should be notified first,
                // but in practice sometimes it is not. See b/4588890
                Log.e(GLSurfaceViewFB.TAG, "eglCreateWindowSurface call failed", ex);
            }
            finally
            {
                if (result == null)
                {
                    try
                    {
                        // Hack to avoid pegged CPU bug
                        Thread.sleep(10);
                    }
                    catch (InterruptedException t)
                    {
                        Log.e(GLSurfaceViewFB.TAG, "CPU was pegged");
                    }
                }
            }

            return result;
        }

        @Override
        public void destroySurface(final android.opengl.EGLDisplay display, final android.opengl.EGLSurface surface)
        {
            if (EGL14.eglDestroySurface(display, surface) == false)
            {
                Log.e(GLSurfaceViewFB.TAG, "eglDestroySurface Failed");
            }
        }
    }

    /**
     * An EGL helper class.
     */

    private static class EglHelper
    {
        public EglHelper(final WeakReference<GLSurfaceViewFB> instanceWeakRef)
        {
            this.mGLViewWeakRef = instanceWeakRef;
        }

        /**
         * Initialize EGL for a given configuration specification
         */
        public void start()
        {
            if (GLSurfaceViewFB.LOG_EGL)
            {
                Log.w(GLSurfaceViewFB.TAG.concat(".EglHelper"), "start() tid=".concat(Long.toString(Thread.currentThread().getId())));
            }

			/*
			 * Get to the default display.
			 */
            this.mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

            if (this.mEglDisplay == EGL14.EGL_NO_DISPLAY)
            {
                throw new RuntimeException("eglGetDisplay failed");
            }

			/*
			 * We can now initialize EGL for that display
			 */
            int[] version = new int[2];
            if (EGL14.eglInitialize(this.mEglDisplay, version, 0, version, 1) == false)
            {
                throw new RuntimeException("eglInitialize failed");
            }

            GLSurfaceViewFB view = this.mGLViewWeakRef.get();

            if (view == null)
            {
                this.mEglConfig = null;
                this.mEglContext = null;
            }
            else
            {
                SharedPreferences phoneInfo = mContext.getSharedPreferences("phoneinfo", MODE_PRIVATE);
                this.mEglConfig = EGL14Config.chooseConfig(this.mEglDisplay, phoneInfo.getBoolean("MSAA",false));
                //this.mEglConfig = EGL14Config.chooseConfig(this.mEglDisplay, false);
				/*
				 * Create an EGL context. We want to do this as rarely as we can, because an EGL context is a somewhat heavy object.
				 */
                this.mEglContext = view.mEGLContextFactory.createContext(this.mEglDisplay, this.mEglConfig);
            }

            if (this.mEglContext == null || this.mEglContext == EGL14.EGL_NO_CONTEXT)
            {
                this.mEglContext = null;
                EglHelper.throwEglException("createContext");
            }

            if (GLSurfaceViewFB.LOG_EGL)
            {
                Log.w(GLSurfaceViewFB.TAG.concat(".EglHelper"), "createContext " + this.mEglContext + " tid=" + Long.toString(Thread.currentThread().getId()));
            }

            this.mEglSurface = null;
        }

        /**
         * Create an egl surface for the current SurfaceHolder surface. If a surface already exists, destroy it before creating the new surface.
         *
         * @return true if the surface was created successfully.
         */
        public boolean createSurface()
        {
            if (GLSurfaceViewFB.LOG_EGL)
            {
                Log.w(GLSurfaceViewFB.TAG.concat(".EglHelper"), "tid=" + Long.toString(Thread.currentThread().getId()));
            }

            if (this.mEglDisplay == null)
            {
                throw new RuntimeException("eglDisplay not initialized");
            }

            if (this.mEglConfig == null)
            {
                throw new RuntimeException("mEglConfig not initialized");
            }

            // The window size has changed, so we need to create a new surface.

            this.destroySurfaceImp();

            // Create an EGL surface we can render into.

            GLSurfaceViewFB view = this.mGLViewWeakRef.get();

            if (view != null)
            {
                this.mEglSurface = view.mEGLWindowSurfaceFactory.createWindowSurface(this.mEglDisplay, this.mEglConfig, view.getHolder());
            }
            else
            {
                this.mEglSurface = null;
            }

            if (this.mEglSurface == null || this.mEglSurface == EGL14.EGL_NO_SURFACE)
            {
                int error = EGL14.eglGetError();

                if (error == EGL14.EGL_BAD_NATIVE_WINDOW)
                {
                    Log.e(GLSurfaceViewFB.TAG.concat(".EglHelper"), "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                }

                return false;
            }

            // Before we can issue GL commands, we need to make sure the context is current and bound to a surface.

            final boolean status = this.makeCurrent();

            return status;
        }

        public boolean makeCurrent()
        {
            if (this.mEglDisplay == null || this.mEglSurface == null || this.mEglContext == null)
            {
                return false;
            }

            if (EGL14.eglMakeCurrent(this.mEglDisplay, this.mEglSurface, this.mEglSurface, this.mEglContext) == false)
            {
                if (EGL14.eglMakeCurrent(this.mEglDisplay, this.mEglSurface, this.mEglSurface, this.mEglContext) == false)
                {
                    if (EGL14.eglMakeCurrent(this.mEglDisplay, this.mEglSurface, this.mEglSurface, this.mEglContext) == false)
                    {
                        final int errorCode = EGL14.eglGetError();

                        // Could not make the context current, probably because the underlying SurfaceView surface has been destroyed.
                        EglHelper.logEglErrorAsWarning(GLSurfaceViewFB.TAG.concat(".EglHelper"), "eglMakeCurrent", errorCode);
                        return false;
                    }
                }
            }

            return true;
        }

        /**
         * Display the current render surface.
         *
         * @return the EGL error code from eglSwapBuffers.
         */
        public int swap()
        {
            if (this.mEglDisplay == null)
            {
                final int error = EGL14.eglGetError();
                return error != 0 ? error : EGL14.EGL_BAD_DISPLAY;
            }

            if (this.mEglSurface == null)
            {
                final int error = EGL14.eglGetError();
                return error != 0 ? error : EGL14.EGL_BAD_SURFACE;
            }

            if (EGL14.eglSwapBuffers(this.mEglDisplay, this.mEglSurface) == false)
            {
                return EGL14.eglGetError();
            }

            return EGL14.EGL_SUCCESS;
        }

        public void destroySurface()
        {
            if (GLSurfaceViewFB.LOG_EGL)
            {
                Log.w(GLSurfaceViewFB.TAG.concat(".EglHelper"), "Destroying surface. tid=" + Long.toString(Thread.currentThread().getId()));
            }

            this.destroySurfaceImp();
        }

        private void destroySurfaceImp()
        {
            if (this.mEglSurface != null && this.mEglSurface != EGL14.EGL_NO_SURFACE)
            {
                EGL14.eglMakeCurrent(this.mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                GLSurfaceViewFB view = this.mGLViewWeakRef.get();

                if (view != null)
                {
                    view.mEGLWindowSurfaceFactory.destroySurface(this.mEglDisplay, this.mEglSurface);
                }

                this.mEglSurface = null;
            }
        }

        public void finish()
        {
            if (GLSurfaceViewFB.LOG_EGL)
            {
                Log.w(GLSurfaceViewFB.TAG.concat(".EglHelper"), "Finishing. tid=" + Long.toString(Thread.currentThread().getId()));
            }

            if (this.mEglContext != null)
            {
                final GLSurfaceViewFB view = this.mGLViewWeakRef.get();

                if (view != null)
                {
                    view.mEGLContextFactory.destroyContext(this.mEglDisplay, this.mEglContext);
                }

                this.mEglContext = null;
            }

            if (this.mEglDisplay != null)
            {
                EGL14.eglTerminate(this.mEglDisplay);
                this.mEglDisplay = null;
            }
        }

        private static void throwEglException(final String function)
        {
            EglHelper.throwEglException(function, EGL14.eglGetError());
        }

        public static void throwEglException(final String function, final int error)
        {
            String message = EglHelper.formatEglError(function, error);

            if (GLSurfaceViewFB.LOG_THREADS)
            {
                Log.e(GLSurfaceViewFB.TAG.concat(".EglHelper"), "EGL Exception. tid=" + Long.toString(Thread.currentThread().getId()) + " Error: " + message);
            }

            throw new RuntimeException(message);
        }

        public static void logEglErrorAsWarning(final String tag, final String function, final int error)
        {
            final String errorMessage = EglHelper.formatEglError(function, error);
            Log.e(tag, errorMessage);

            EglHelper.cacheError(errorMessage);
        }

        public static String formatEglError(final String function, final int error)
        {
            return function + " failed: " + GLSurfaceViewFB.getErrorString(error);
        }

        private static void cacheError(final String errorMessage)
        {
            try
            {
                if (EglHelper.cachedErrors == null)
                {
                    EglHelper.cachedErrors = new HashMap<>();
                }

                int errorCount = 0;

                if (EglHelper.cachedErrors.containsKey(errorMessage) == true)
                {
                    errorCount = EglHelper.cachedErrors.get(errorMessage).intValue();
                    errorCount++;
                }

                if (EglHelper.cachedErrors.size() < 100)
                {
                    EglHelper.cachedErrors.put(errorMessage, Integer.valueOf(errorCount));
                }
            }
            catch (Exception ex)
            {
                Log.e(GLSurfaceViewFB.TAG, "Failed to cache error.", ex);
            }
        }

        private final WeakReference<GLSurfaceViewFB>	mGLViewWeakRef;
        android.opengl.EGLDisplay						mEglDisplay;
        android.opengl.EGLSurface						mEglSurface;
        android.opengl.EGLConfig						mEglConfig;
        android.opengl.EGLContext						mEglContext;

        protected static HashMap<String, Integer>		cachedErrors	= null;
    }

    /**
     * Returns any cached error as a log
     *
     * @return Error log
     */
    public static String getCachedErrorsLog()
    {
        String log = "<NO ERRORS>";

        try
        {
            if (EglHelper.cachedErrors != null && EglHelper.cachedErrors.size() > 0)
            {
                log = "";

                for (Map.Entry<String, Integer> entry : EglHelper.cachedErrors.entrySet())
                {
                    log = log.concat("\n>>").concat(entry.getKey()).concat(" (").concat(entry.getValue().toString()).concat(")");

                    if (log.length() > 300)
                    {
                        log = log.concat("<<<<<Log Too Large>>>>>");
                        break;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Log.e(GLSurfaceViewFB.TAG, "Failed to cache error.", ex);
            log = "Failed to cache error.";
        }

        return log;
    }

    /**
     * A generic GL Thread. Takes care of initializing EGL and GL. Delegates to a IRendererEGL14 instance to do the actual drawing. Can be configured
     * to render continuously or on request.
     *
     * All potentially blocking synchronization is done through the sGLThreadManager object. This avoids multiple-lock ordering issues.
     *
     */
    static class GLThread extends Thread
    {
        GLThread(final WeakReference<GLSurfaceViewFB> instanceWeakRef)
        {
            super();
            this.mWidth = 0;
            this.mHeight = 0;
            this.mRequestRender = true;
            this.mRenderMode = GLSurfaceViewFB.RENDERMODE_CONTINUOUSLY;
            this.mGLViewWeakRef = instanceWeakRef;
        }

        @Override
        public void run()
        {
            this.setName("GLThread " + Long.toString(this.getId()));

            if (GLSurfaceViewFB.LOG_THREADS)
            {
                Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "starting tid=" + Long.toString(this.getId()));
            }

            try
            {
                this.guardedRun();
            }
            catch (InterruptedException e)
            {
                // fall thru and exit normally
            }
            finally
            {
                GLSurfaceViewFB.sGLThreadManager.threadExiting(this);
            }
        }

        /*
         * This private method should only be called inside a synchronized(sGLThreadManager) block.
         */
        private void stopEglSurfaceLocked()
        {
            if (this.mHaveEglSurface)
            {
                this.mHaveEglSurface = false;
                this.mEglHelper.destroySurface();
            }
        }

        /*
         * This private method should only be called inside a synchronized(sGLThreadManager) block.
         */
        private void stopEglContextLocked()
        {
            final GLSurfaceViewFB view = this.mGLViewWeakRef.get();

            if (view != null && view.mRenderer != null)
            {
                view.mRenderer.onDestroy();
            }

            if (this.mHaveEglContext)
            {
                this.mEglHelper.finish();
                this.mHaveEglContext = false;
                GLSurfaceViewFB.sGLThreadManager.releaseEglContextLocked(this);
            }
        }

        private void guardedRun() throws InterruptedException
        {
            this.mEglHelper = new EglHelper(this.mGLViewWeakRef);
            this.mHaveEglContext = false;
            this.mHaveEglSurface = false;

            try
            {
                boolean createEglContext = false;
                boolean createEglSurface = false;
                boolean createGlInterface = false;
                boolean lostEglContext = false;
                boolean sizeChanged = false;
                boolean wantRenderNotification = false;
                boolean doRenderNotification = false;
                boolean askedToReleaseEglContext = false;
                int w = 0;
                int h = 0;
                Runnable event = null;

                while (true)
                {
                    synchronized (GLSurfaceViewFB.sGLThreadManager)
                    {
                        while (true)
                        {
                            if (this.mShouldExit == true)
                            {
                                return;
                            }

                            if (this.mEventQueue.isEmpty() == false)
                            {
                                event = this.mEventQueue.remove(0);
                                break;
                            }

                            // Update the pause state.
                            boolean pausing = false;

                            if (this.mPaused != this.mRequestPaused)
                            {
                                pausing = this.mRequestPaused;
                                this.mPaused = this.mRequestPaused;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                                if (GLSurfaceViewFB.LOG_PAUSE_RESUME)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "mPaused is now " + Boolean.toString(this.mPaused) + ". tid=" + Long.toString(this.getId()));
                                }
                            }

                            // Do we need to give up the EGL context?
                            if (this.mShouldReleaseEglContext == true)
                            {
                                if (GLSurfaceViewFB.LOG_SURFACE)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Releasing EGL context because asked to tid=" + Long.toString(this.getId()));
                                }

                                this.stopEglSurfaceLocked();
                                this.stopEglContextLocked();
                                this.mShouldReleaseEglContext = false;
                                askedToReleaseEglContext = true;
                            }

                            // Have we lost the EGL context?
                            if (lostEglContext == true)
                            {
                                this.stopEglSurfaceLocked();
                                this.stopEglContextLocked();
                                lostEglContext = false;
                            }

                            // When pausing, release the EGL surface:
                            if (pausing == true && this.mHaveEglSurface == true)
                            {
                                if (GLSurfaceViewFB.LOG_SURFACE)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Releasing EGL surface because paused tid=" + Long.toString(this.getId()));
                                }

                                this.stopEglSurfaceLocked();
                            }

                            // When pausing, optionally release the EGL Context:
                            if (pausing == true && this.mHaveEglContext == true)
                            {
                                GLSurfaceViewFB view = this.mGLViewWeakRef.get();
                                boolean preserveEglContextOnPause = view == null ? false : view.mPreserveEGLContextOnPause;

                                if (preserveEglContextOnPause == false || GLSurfaceViewFB.sGLThreadManager.shouldReleaseEGLContextWhenPausing())
                                {
                                    this.stopEglContextLocked();

                                    if (GLSurfaceViewFB.LOG_SURFACE)
                                    {
                                        Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Releasing EGL context because paused tid=" + Long.toString(this.getId()));
                                    }
                                }
                            }

                            // Have we lost the SurfaceView surface?
                            if ((this.mHasSurface == false) && (this.mWaitingForSurface == false))
                            {
                                if (GLSurfaceViewFB.LOG_SURFACE)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Noticed surfaceView surface lost tid=" + Long.toString(this.getId()));
                                }

                                if (this.mHaveEglSurface == true)
                                {
                                    this.stopEglSurfaceLocked();
                                }

                                this.mWaitingForSurface = true;
                                this.mSurfaceIsBad = false;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }

                            // Have we acquired the surface view surface?
                            if (this.mHasSurface == true && this.mWaitingForSurface == true)
                            {
                                if (GLSurfaceViewFB.LOG_SURFACE)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Noticed surfaceView surface acquired tid=" + Long.toString(this.getId()));
                                }

                                this.mWaitingForSurface = false;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }

                            if (doRenderNotification == true)
                            {
                                if (GLSurfaceViewFB.LOG_SURFACE)
                                {
                                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Sending render notification tid=" + Long.toString(this.getId()));
                                }

                                wantRenderNotification = false;
                                doRenderNotification = false;
                                this.mRenderComplete = true;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }

                            // Ready to draw?
                            if (this.readyToDraw() == true)
                            {

                                // If we don't have an EGL context, try to acquire one.
                                if (this.mHaveEglContext == false)
                                {
                                    if (askedToReleaseEglContext == true)
                                    {
                                        askedToReleaseEglContext = false;
                                    }
                                    else if (GLSurfaceViewFB.sGLThreadManager.tryAcquireEglContextLocked(this))
                                    {
                                        try
                                        {
                                            this.mEglHelper.start();
                                        }
                                        catch (RuntimeException t)
                                        {
                                            GLSurfaceViewFB.sGLThreadManager.releaseEglContextLocked(this);
                                            throw t;
                                        }

                                        this.mHaveEglContext = true;
                                        createEglContext = true;

                                        GLSurfaceViewFB.sGLThreadManager.notifyAll();
                                    }
                                }

                                if (this.mHaveEglContext == true && this.mHaveEglSurface == false)
                                {
                                    this.mHaveEglSurface = true;
                                    createEglSurface = true;
                                    createGlInterface = true;
                                    sizeChanged = true;
                                }

                                if (this.mHaveEglSurface == true)
                                {
                                    if (this.mSizeChanged == true)
                                    {
                                        sizeChanged = true;
                                        w = this.mWidth;
                                        h = this.mHeight;
                                        wantRenderNotification = true;

                                        if (GLSurfaceViewFB.LOG_SURFACE)
                                        {
                                            Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "Noticing that we want render notification tid=" + Long.toString(this.getId()));
                                        }

                                        // Destroy and recreate the EGL surface.
                                        createEglSurface = true;

                                        this.mSizeChanged = false;
                                    }

                                    this.mRequestRender = false;
                                    GLSurfaceViewFB.sGLThreadManager.notifyAll();
                                    break;
                                }
                            }

                            // By design, this is the only place in a GLThread thread where we wait().
                            if (GLSurfaceViewFB.LOG_THREADS_WAIT)
                            {
                                Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "waiting tid=" + Long.toString(this.getId()) + " mHaveEglContext: " + Boolean.toString(this.mHaveEglContext) + " mHaveEglSurface: " + Boolean.toString(this.mHaveEglSurface) + " mFinishedCreatingEglSurface: " + Boolean.toString(this.mFinishedCreatingEglSurface) + " mPaused: " + Boolean.toString(this.mPaused) + " mHasSurface: " + Boolean.toString(this.mHasSurface) + " mSurfaceIsBad: "
                                        + Boolean.toString(this.mSurfaceIsBad) + " mWaitingForSurface: " + Boolean.toString(this.mWaitingForSurface) + " mWidth: " + Integer.toString(this.mWidth) + " mHeight: " + Integer.toString(this.mHeight) + " mRequestRender: " + Boolean.toString(this.mRequestRender) + " mRenderMode: " + Integer.toString(this.mRenderMode));
                            }

                            GLSurfaceViewFB.sGLThreadManager.wait();
                        }
                    } // end of synchronized(sGLThreadManager)

                    if (event != null)
                    {
                        event.run();
                        event = null;
                        continue;
                    }

                    if (createEglSurface == true)
                    {
                        if (GLSurfaceViewFB.LOG_SURFACE)
                        {
                            Log.w(GLSurfaceViewFB.TAG.concat(".GLThread"), "egl createSurface");
                        }

                        if (this.mEglHelper.createSurface() == true)
                        {
                            synchronized (GLSurfaceViewFB.sGLThreadManager)
                            {
                                this.mFinishedCreatingEglSurface = true;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }
                        }
                        else
                        {
                            synchronized (GLSurfaceViewFB.sGLThreadManager)
                            {
                                this.mFinishedCreatingEglSurface = false;
                                this.mSurfaceIsBad = false;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }

                            continue;
                        }

                        createEglSurface = false;
                    }

                    if (createGlInterface == true)
                    {
                        createGlInterface = false;
                    }

                    if (createEglContext == true)
                    {
                        if (GLSurfaceViewFB.LOG_RENDERER)
                        {
                            Log.w(GLSurfaceViewFB.TAG.concat(".GLThread"), "onSurfaceCreated");
                        }

                        GLSurfaceViewFB view = this.mGLViewWeakRef.get();

                        if (view != null)
                        {
                            view.mRenderer.onSurfaceCreated();
                        }

                        createEglContext = false;
                    }

                    if (sizeChanged == true)
                    {
                        if (GLSurfaceViewFB.LOG_RENDERER)
                        {
                            Log.w(GLSurfaceViewFB.TAG.concat(".GLThread"), "onSurfaceChanged(" + Integer.toString(w) + ", " + Integer.toString(h) + ")");
                        }

                        GLSurfaceViewFB view = this.mGLViewWeakRef.get();

                        if (view != null)
                        {
                            view.mRenderer.onSurfaceChanged(w, h);
                        }

                        sizeChanged = false;
                    }

                    if (GLSurfaceViewFB.LOG_RENDERER_DRAW_FRAME)
                    {
                        Log.w(GLSurfaceViewFB.TAG.concat(".GLThread"), "onDrawFrame tid=" + Long.toString(this.getId()));
                    }
                    {
                        GLSurfaceViewFB view = this.mGLViewWeakRef.get();

                        if (view != null)
                        {
                            view.mRenderer.onDrawFrame();
                        }
                    }

                    int swapError = this.mEglHelper.swap();
                    // Thread.sleep(2);

                    switch (swapError)
                    {
                        case EGL14.EGL_SUCCESS:
                            break;

                        case EGL14.EGL_CONTEXT_LOST:

                            if (GLSurfaceViewFB.LOG_SURFACE)
                            {
                                Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "egl context lost tid=" + Long.toString(this.getId()));
                            }

                            lostEglContext = true;
                            break;

                        default:

                            // Other errors typically mean that the current surface is bad,
                            // probably because the SurfaceView surface has been destroyed,
                            // but we haven't been notified yet.
                            // Log the error to help developers understand why rendering stopped.
                            EglHelper.logEglErrorAsWarning(GLSurfaceViewFB.TAG.concat(".GLThread"), "eglSwapBuffers", swapError);

                            synchronized (GLSurfaceViewFB.sGLThreadManager)
                            {
                                this.mSurfaceIsBad = true;
                                GLSurfaceViewFB.sGLThreadManager.notifyAll();
                            }
                            break;
                    }

                    if (wantRenderNotification == true)
                    {
                        doRenderNotification = true;
                    }
                }
            }
            finally
            {
				/*
				 * clean-up everything...
				 */
                synchronized (GLSurfaceViewFB.sGLThreadManager)
                {
                    this.stopEglSurfaceLocked();
                    this.stopEglContextLocked();
                }
            }
        }

        public boolean ableToDraw()
        {
            return this.mHaveEglContext && this.mHaveEglSurface && this.readyToDraw();
        }

        private boolean readyToDraw()
        {
            return (!this.mPaused) && this.mHasSurface && (!this.mSurfaceIsBad) && (this.mWidth > 0) && (this.mHeight > 0) && (this.mRequestRender || (this.mRenderMode == GLSurfaceViewFB.RENDERMODE_CONTINUOUSLY));
        }

        public void setRenderMode(final int renderMode)
        {
            if (!((GLSurfaceViewFB.RENDERMODE_WHEN_DIRTY <= renderMode) && (renderMode <= GLSurfaceViewFB.RENDERMODE_CONTINUOUSLY)))
            {
                throw new IllegalArgumentException("renderMode");
            }
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                this.mRenderMode = renderMode;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();
            }
        }

        public int getRenderMode()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                return this.mRenderMode;
            }
        }

        public void requestRender()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                if (this.mRequestPaused == false)
                {
                    this.mRequestRender = true;
                    GLSurfaceViewFB.sGLThreadManager.notifyAll();
                }
            }
        }

        public void surfaceCreated()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                if (GLSurfaceViewFB.LOG_THREADS)
                {
                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "surfaceCreated tid=" + Long.toString(this.getId()));
                }

                this.mHasSurface = true;
                this.mFinishedCreatingEglSurface = false;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                while ((this.mWaitingForSurface) && (!this.mFinishedCreatingEglSurface) && (!this.mExited))
                {
                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void surfaceDestroyed()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                if (GLSurfaceViewFB.LOG_THREADS)
                {
                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "surfaceDestroyed tid=" + Long.toString(this.getId()));
                }

                this.mHasSurface = false;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                while ((this.mWaitingForSurface == false) && (this.mExited == false))
                {
                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void onPause()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                if (GLSurfaceViewFB.LOG_PAUSE_RESUME)
                {
                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "onPause tid=" + Long.toString(this.getId()));
                }

                this.mRequestPaused = true;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                while ((this.mExited == false) && (this.mPaused == false))
                {
                    if (GLSurfaceViewFB.LOG_PAUSE_RESUME)
                    {
                        Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "onPause waiting for mPaused.");
                    }

                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void onResume()
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                if (GLSurfaceViewFB.LOG_PAUSE_RESUME)
                {
                    Log.i(GLSurfaceViewFB.TAG.concat(".GLThread"), "onResume tid=" + Long.toString(this.getId()));
                }

                this.mRequestPaused = false;
                this.mRequestRender = true;
                this.mRenderComplete = false;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                while ((this.mExited == false) && this.mPaused == true && (this.mRenderComplete == false))
                {
                    if (GLSurfaceViewFB.LOG_PAUSE_RESUME)
                    {
                        Log.i(GLSurfaceViewFB.TAG.concat(".Main-Thread"), "onResume waiting for !mPaused.");
                    }

                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void onWindowResize(final int w, final int h)
        {
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                this.mWidth = w;
                this.mHeight = h;
                this.mSizeChanged = true;
                this.mRequestRender = true;
                this.mRenderComplete = false;

                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                // Wait for thread to react to resize and render a frame
                while (this.mExited == false && this.mPaused == false && this.mRenderComplete == false && this.ableToDraw() == true)
                {
                    if (GLSurfaceViewFB.LOG_SURFACE)
                    {
                        Log.i(GLSurfaceViewFB.TAG.concat(".Main-Thread"), "onWindowResize waiting for render complete from tid=" + Long.toString(this.getId()));
                    }

                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void requestExitAndWait()
        {
            // don't call this from GLThread thread or it is a guaranteed deadlock!
            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                this.mShouldExit = true;
                GLSurfaceViewFB.sGLThreadManager.notifyAll();

                while (this.mExited == false)
                {
                    try
                    {
                        GLSurfaceViewFB.sGLThreadManager.wait();
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public void requestReleaseEglContextLocked()
        {
            this.mShouldReleaseEglContext = true;
            GLSurfaceViewFB.sGLThreadManager.notifyAll();
        }

        /**
         * Queue an "event" to be run on the GL rendering thread.
         *
         * @param runnable
         *            the runnable to be run on the GL rendering thread.
         */
        public void queueEvent(final Runnable runnable)
        {
            if (runnable == null)
            {
                throw new IllegalArgumentException("'runnable' must not be null");
            }

            synchronized (GLSurfaceViewFB.sGLThreadManager)
            {
                this.mEventQueue.add(runnable);
                Log.d(GLSurfaceViewFB.TAG, "Queued events: ".concat(this.mEventQueue == null ? "0" : Integer.toString(this.mEventQueue.size())));

                GLSurfaceViewFB.sGLThreadManager.notifyAll();
            }
        }

        // Once the thread is started, all accesses to the following member
        // variables are protected by the sGLThreadManager monitor
        private boolean									mShouldExit;
        protected boolean								mExited;
        private boolean									mRequestPaused;
        private boolean									mPaused;
        private boolean									mHasSurface;
        private boolean									mSurfaceIsBad;
        private boolean									mWaitingForSurface;
        private boolean									mHaveEglContext;
        private boolean									mHaveEglSurface;
        private boolean									mFinishedCreatingEglSurface;
        private boolean									mShouldReleaseEglContext;
        private int										mWidth;
        private int										mHeight;
        private int										mRenderMode;
        private boolean									mRequestRender;
        private boolean									mRenderComplete;
        public final ArrayList<Runnable>				mEventQueue		= new ArrayList<>();
        private boolean									mSizeChanged	= true;

        // End of member variables protected by the sGLThreadManager monitor.

        private EglHelper								mEglHelper;

        /**
         * Set once at thread construction time, nulled out when the parent view is garbage called. This weak reference allows the GLSurfaceViewEGL14
         * to be garbage collected while the GLThread is still alive.
         */
        private final WeakReference<GLSurfaceViewFB>	mGLViewWeakRef;
    }

    private void checkRenderThreadState()
    {
        if (this.mGLThread != null)
        {
            throw new IllegalStateException("setRenderer has already been called for this instance.");
        }
    }

    private static class GLThreadManager
    {
        public GLThreadManager()
        {
            Log.i(GLSurfaceViewFB.TAG.concat(".GLThreadManager"), "GLThreadManager instance created");
        }

        public synchronized void threadExiting(final GLThread thread)
        {
            if (GLSurfaceViewFB.LOG_THREADS)
            {
                Log.i(GLSurfaceViewFB.TAG.concat(".GLThreadManager"), "Exiting tid=" + Long.toString(thread.getId()));
            }

            thread.mExited = true;

            if (this.mEglOwner == thread)
            {
                this.mEglOwner = null;
            }

            this.notifyAll();
        }

        /*
         * Tries once to acquire the right to use an EGL context. Does not block. Requires that we are already in the sGLThreadManager monitor when
         * this is called.
         *
         * @return true if the right to use an EGL context was acquired.
         */
        public boolean tryAcquireEglContextLocked(final GLThread thread)
        {
            if (this.mEglOwner == thread || this.mEglOwner == null)
            {
                this.mEglOwner = thread;
                this.notifyAll();
            }

            return true;
        }

        /*
         * Releases the EGL context. Requires that we are already in the sGLThreadManager monitor when this is called.
         */
        public void releaseEglContextLocked(final GLThread thread)
        {
            if (this.mEglOwner == thread)
            {
                this.mEglOwner = null;
            }

            this.notifyAll();
        }

        public synchronized boolean shouldReleaseEGLContextWhenPausing()
        {
            // Release the EGL context when pausing even if
            // the hardware supports multiple EGL contexts.
            // Otherwise the device could run out of EGL contexts.
            return this.mLimitedGLESContexts;
        }

        /**
         * This check was required for some pre-Android-3.0 hardware. Android 3.0 provides support for hardware-accelerated views, therefore multiple
         * EGL contexts are supported on all Android 3.0+ EGL drivers.
         */
        private boolean		mLimitedGLESContexts;
        private GLThread	mEglOwner;
    }

    /**
     * Gets a GL Error string
     *
     * @param error
     *            Error to be resolve
     * @return Resolved error string
     */
    protected static String getErrorString(final int error)
    {
        Thread.dumpStack();

        switch (error)
        {
            case EGL14.EGL_SUCCESS:
                return "EGL_SUCCESS";
            case EGL14.EGL_NOT_INITIALIZED:
                return "EGL_NOT_INITIALIZED";
            case EGL14.EGL_BAD_ACCESS:
                return "EGL_BAD_ACCESS";
            case EGL14.EGL_BAD_ALLOC:
                return "EGL_BAD_ALLOC";
            case EGL14.EGL_BAD_ATTRIBUTE:
                return "EGL_BAD_ATTRIBUTE";
            case EGL14.EGL_BAD_CONFIG:
                return "EGL_BAD_CONFIG";
            case EGL14.EGL_BAD_CONTEXT:
                return "EGL_BAD_CONTEXT";
            case EGL14.EGL_BAD_CURRENT_SURFACE:
                return "EGL_BAD_CURRENT_SURFACE";
            case EGL14.EGL_BAD_DISPLAY:
                return "EGL_BAD_DISPLAY";
            case EGL14.EGL_BAD_MATCH:
                return "EGL_BAD_MATCH";
            case EGL14.EGL_BAD_NATIVE_PIXMAP:
                return "EGL_BAD_NATIVE_PIXMAP";
            case EGL14.EGL_BAD_NATIVE_WINDOW:
                return "EGL_BAD_NATIVE_WINDOW";
            case EGL14.EGL_BAD_PARAMETER:
                return "EGL_BAD_PARAMETER";
            case EGL14.EGL_BAD_SURFACE:
                return "EGL_BAD_SURFACE";
            case EGL14.EGL_CONTEXT_LOST:
                return "EGL_CONTEXT_LOST";
            default:
                return "0x" + Integer.toHexString(error);
        }
    }

    protected static final GLThreadManager			sGLThreadManager	= new GLThreadManager();

    private final WeakReference<GLSurfaceViewFB>	mThisWeakRef		= new WeakReference<>(this);
    private GLThread								mGLThread;
    protected IRendererEGL14						mRenderer;
    private boolean									mDetached;
    protected EGLContextFactory						mEGLContextFactory;
    protected EGLWindowSurfaceFactory				mEGLWindowSurfaceFactory;
    protected int									mDebugFlags;
    protected int									mEGLContextClientVersion;
    protected boolean								mPreserveEGLContextOnPause;
    protected boolean								mRecordable;

    public interface IRendererEGL14
    {
        /**
         * Called when the surface is created or recreated.
         * <p>
         * Called when the rendering thread starts and whenever the EGL context is lost. The EGL context will typically be lost when the Android device
         * awakes after going to sleep.
         * <p>
         * Since this method is called at the beginning of rendering, as well as every time the EGL context is lost, this method is a convenient place to
         * put code to create resources that need to be created when the rendering starts, and that need to be recreated when the EGL context is lost.
         * Textures are an example of a resource that you might want to create here.
         * <p>
         * Note that when the EGL context is lost, all OpenGL resources associated with that context will be automatically deleted. You do not need to
         * call the corresponding "glDelete" methods such as glDeleteTextures to manually delete these lost resources.
         */
        void onSurfaceCreated();

        /**
         * Called when the surface changed size.
         * <p>
         * Called after the surface is created and whenever the OpenGL ES surface size changes.
         * <p>
         * Typically you will set your viewport here. If your camera is fixed then you could also set your projection matrix here:
         *
         * <pre class="prettyprint">
         * void onSurfaceChanged(int width, int height)
         * {
         * 	gl.glViewport(0, 0, width, height);
         * 	// for a fixed camera, set the projection too
         * 	float ratio = (float)width / height;
         * 	gl.glMatrixMode(GL10.GL_PROJECTION);
         * 	gl.glLoadIdentity();
         * 	gl.glFrustumf(-ratio, ratio, -1, 1, 1, 10);
         * }
         * </pre>
         *
         * @param width
         *            Surface width
         * @param height
         *            Surface height
         */
        void onSurfaceChanged(final int width, final int height);

        /**
         * Called to draw the current frame.
         * <p>
         * This method is responsible for drawing the current frame.
         * <p>
         * The implementation of this method typically looks like this:
         *
         * <pre class="prettyprint">
         * void onDrawFrame()
         * {
         * 	gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
         * 	// ... other gl calls to render the scene ...
         * }
         * </pre>
         */
        void onDrawFrame();

        /**
         * Called when the GL thread is exiting
         */
        void onDestroy();
    }
}
