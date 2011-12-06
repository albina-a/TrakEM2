package ini.trakem2.display;

import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

public class NonLinearTransformMode extends GroupingMode {

	protected void doPainterUpdate( final Rectangle r, final double m )
	{
		try
		{
			final CoordinateTransform mlst = createCT();
			final SimilarityModel2D toWorld = new SimilarityModel2D();
			toWorld.set( 1.0f / ( float )m, 0, r.x - ScreenPatchRange.pad / ( float )m, r.y - ScreenPatchRange.pad / ( float )m );
			
			final mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform > ctl = new mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform >();
			ctl.add( toWorld );
			ctl.add( mlst );
			ctl.add( toWorld.createInverse() );
			
			final CoordinateTransformMesh ctm = new CoordinateTransformMesh( ctl, 32, r.width * ( float )m + 2 * ScreenPatchRange.pad, r.height * ( float )m + 2 * ScreenPatchRange.pad );
			final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( ctm );
			
			final HashMap<Paintable, GroupingMode.ScreenPatchRange<?>> screenPatchRanges = this.screenPatchRanges; // keep a pointer to the current list
			for ( final GroupingMode.ScreenPatchRange spr : screenPatchRanges.values())
			{
				if (screenPatchRanges != this.screenPatchRanges) {
					// List has been updated; restart painting
					// TODO should it call itself:  doPainterUpdate( r, m );
					break;
				}
				spr.update( mapping );
			}
		}
		catch ( NotEnoughDataPointsException e ) {}
		catch ( NoninvertibleModelException e ) {}
		catch ( IllDefinedDataPointsException e ) {}
		catch ( Exception e ) { e.printStackTrace(); }
	}

	private class NonLinearTransformSource extends GroupingMode.GroupedGraphicsSource {
		public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			
			final Stroke original_stroke = g.getStroke();
			final AffineTransform original = g.getTransform();
			g.setTransform( new AffineTransform() );
			g.setStroke( new BasicStroke( 1.0f ) );
			for ( final Point p : points )
			{
				final float[] w = p.getW();
				Utils.drawPoint( g, Math.round( ( float )magnification * ( w[ 0 ] - srcRect.x ) ), Math.round( ( float )magnification * ( w[ 1 ] - srcRect.y ) ) );
			}
			
			g.setTransform( original );
			g.setStroke( original_stroke );
		}
	}

	protected ScreenPatchRange createScreenPathRange(final PatchRange range, final Rectangle srcRect, final double magnification) {
		return new NonLinearTransformMode.ScreenPatchRange(range, srcRect, magnification);
	}

	private static class ScreenPatchRange extends GroupingMode.ScreenPatchRange<TransformMeshMappingWithMasks<?>> {
		ScreenPatchRange( final PatchRange range, final Rectangle srcRect, final double magnification ) {
			super(range, srcRect, magnification);
		}
		public void update( final TransformMeshMappingWithMasks< ? > mapping )
		{
			ipTransformed.reset();
			if ( mask == null )
			{
				mapping.map( ip, ipTransformed );
			}
			else
			{
				maskTransformed.reset();
				final ImageProcessorWithMasks ipm = new ImageProcessorWithMasks( ip, mask, null );
				final ImageProcessorWithMasks tpm = new ImageProcessorWithMasks( ipTransformed, maskTransformed, null );
				mapping.map( ipm, tpm );
			}
			if (null != transformedImage) transformedImage.flush();
			transformedImage = super.makeImage( ipTransformed, maskTransformed );
		}
	}

	protected GroupingMode.GroupedGraphicsSource createGroupedGraphicSource() {
		return new NonLinearTransformSource();
	}

	public NonLinearTransformMode(final Display display, final List<Displayable> selected) {
		super(display, selected);
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		super.initThreads();
	}

	public NonLinearTransformMode(final Display display) {
		this(display, display.getSelection().getSelected());
	}

	private Collection< Point > points = new ArrayList< Point >();
	
	private Point p_clicked = null;
	
	public void mousePressed( MouseEvent me, int x_p, int y_p, double magnification )
	{
		/* find if clicked on a point */
		p_clicked = null;
		float min = Float.MAX_VALUE;
		final Point mouse = new Point( new float[]{ x_p, y_p } );
		final float a = ( float )( 64 / magnification / magnification);
		for ( final Point p : points )
		{
			final float sd = Point.squareDistance( p, mouse );
			if ( sd < min && sd < a )
			{
				p_clicked = p;
				min = sd;
			}
		}

		if ( me.isShiftDown() )
		{
			if ( null == p_clicked )
			{
				/* add one */
				try
				{
					if ( points.size() > 0 )
					{
						/* 
						 * Create a pseudo-invertible (TransformMesh) for the screen.
						 */
						final CoordinateTransform mlst = createCT();
						final SimilarityModel2D toWorld = new SimilarityModel2D();
						toWorld.set( 1.0f / ( float )magnification, 0, srcRect.x, srcRect.y );
						final SimilarityModel2D toScreen = toWorld.createInverse();
						
						final mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform > ctl = new mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform >();
						ctl.add( toWorld );
						ctl.add( mlst );
						ctl.add( toScreen );
				
						final CoordinateTransformMesh ctm = new CoordinateTransformMesh(
								ctl,
								32,
								( int )Math.ceil( srcRect.width * magnification ),
								( int )Math.ceil( srcRect.height * magnification ) );
						
						final float[] l = mouse.getL();
						toScreen.applyInPlace( l );
						ctm.applyInverseInPlace( l );
						toWorld.applyInPlace( l );
					}
					points.add( mouse );
					p_clicked = mouse;
				}
				catch ( Exception e )
				{
					Utils.log( "Could not add point" );
					e.printStackTrace();
				}
			}
			else if ( Utils.isControlDown( me ) )
			{
				// remove it
				points.remove(p_clicked);
			 	p_clicked = null;
			}
		}
	}

	public void mouseDragged( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old )
	{
		if ( null != p_clicked )
		{
			final float[] w = p_clicked.getW();
			w[ 0 ] += x_d - x_d_old;
			w[ 1 ] += y_d - y_d_old;
			painter.update();
		}
	}
	public void mouseReleased( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r )
	{
		// bring to screen coordinates
		mouseDragged( me, x_p, y_p, x_r, y_r, x_d, y_d );
		p_clicked = null; // so isDragging can return the right state
	}
	
	public boolean isDragging()
	{
		return null != p_clicked;
	}

	private final void setUndoState() {
		layer.getParent().addEditStep(new Displayable.DoEdits(new HashSet<Displayable>(originalPatches)).init(new String[]{"data", "at", "width", "height"}));
	}
	
	final private Future< Boolean > applyToPatch( final Patch patch ) throws Exception
	{
		final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
		final AffineTransform pat = new AffineTransform();
		pat.translate( -pbox.x, -pbox.y );
		pat.preConcatenate( patch.getAffineTransform() );
		
		final AffineModel2D toWorld = new AffineModel2D();
		toWorld.set( pat );
		
		final CoordinateTransform mlst = createCT();
		
		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		ctl.add( toWorld );
		ctl.add( mlst );
		ctl.add( toWorld.createInverse() );
		
		patch.appendCoordinateTransform( ctl );
		return patch.updateMipMaps();
	}
	
	public boolean apply()
	{
		return apply( null );
	}

	public boolean apply( final Set< Layer > sublist )
	{

		/* Set undo step to reflect initial state before any transformations */
		setUndoState();
		
		Bureaucrat.createAndStart( new Worker.Task( "Applying transformations" )
		{
			public void exec()
			{
				final ArrayList< Future< Boolean > > futures = new ArrayList< Future< Boolean > >();

				synchronized ( updater )
				{
					/* apply to selected patches */
					for ( final Paintable p : screenPatchRanges.keySet() )
					{
						if ( p instanceof Patch )
						{
							try
							{
								futures.add( applyToPatch( ( Patch )p ) );
							}
							catch ( Exception e )
							{
								e.printStackTrace();
							}
						}
					}
					/* apply to other layers if there are any */
					if ( !( sublist == null || sublist.isEmpty() ) )
					{
						for ( final Layer layer : sublist )
						{
							for ( final Displayable p : layer.getDisplayables( Patch.class ) )
							{
								try
								{
									futures.add( applyToPatch( ( Patch )p ) );
								}
								catch ( Exception e )
								{
									e.printStackTrace();
								}
							}
						}
					}
				}

				/* Flush images */
				for ( GroupingMode.ScreenPatchRange<?> spr : new HashSet< GroupingMode.ScreenPatchRange<?> >( screenPatchRanges.values() ) )
				{
					spr.flush();
				}

				/* Wait until all mipmaps are regenerated */
				for ( Future<?> fu : futures )
					try
					{
						fu.get();
					}
					catch ( Exception ie )
					{}

				// Set undo step to reflect final state after applying transformations
				setUndoState();

			}
		}, layer.getProject() );

		super.quitThreads();

		return true;
	}

	private CoordinateTransform createCT() throws Exception
	{
		final Collection< PointMatch > pm = new ArrayList<PointMatch>();
		for ( Point p : points )
		{
			pm.add( new PointMatch( new Point( p.getL() ), new Point( p.getW() ) ) );
		}
		/*
		 * TODO replace this with the desired parameters of the transformation
		 */
		final MovingLeastSquaresTransform2 mlst = new MovingLeastSquaresTransform2();
		mlst.setAlpha( 1.0f );
		Class< ? extends AbstractAffineModel2D< ? > > c = AffineModel2D.class;
		switch (points.size()) {
			case 1:
				c = TranslationModel2D.class;
				break;
			case 2:
				c = SimilarityModel2D.class;
				break;
			default:
				break;
		}
		mlst.setModel( c );
		mlst.setMatches( pm );
		
		return mlst;
	}
}
