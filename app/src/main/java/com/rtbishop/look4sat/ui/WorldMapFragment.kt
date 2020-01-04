/*
 * LookingSat. Amateur radio & weather satellite tracker and passes calculator.
 * Copyright (C) 2019 Arty Bishop (bishop.arty@gmail.com)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.rtbishop.look4sat.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.amsacode.predict4java.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.rtbishop.look4sat.MainViewModel
import com.rtbishop.look4sat.R
import com.rtbishop.look4sat.repo.SatPass
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WorldMapFragment : Fragment() {

    private lateinit var service: ScheduledExecutorService
    private lateinit var mainActivity: MainActivity
    private lateinit var viewModel: MainViewModel
    private lateinit var mapFrame: FrameLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var trackView: TrackView
    private lateinit var predictor: PassPredictor
    private lateinit var selectedSat: TLE
    private lateinit var gsp: GroundStationPosition
    private lateinit var satPassList: List<SatPass>
    private var checkedItem = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = Executors.newSingleThreadScheduledExecutor()
        mainActivity = activity as MainActivity
        viewModel = ViewModelProvider(mainActivity).get(MainViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_worldmap, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findViews(view)
        setupComponents()
    }

    private fun findViews(view: View) {
        mapFrame = view.findViewById(R.id.worldmap_frame)
        fab = view.findViewById(R.id.worldmap_fab)
    }

    private fun setupComponents() {
        val delay = viewModel.delay
        gsp = viewModel.gsp.value ?: GroundStationPosition(0.0, 0.0, 0.0)
        satPassList = viewModel.passSatList.value ?: emptyList()

        if (satPassList.isNotEmpty()) {
            satPassList = satPassList.distinctBy { it.tle }
            satPassList = satPassList.sortedBy { it.tle.name }
            fab.setOnClickListener { showSelectSatDialog(satPassList) }
            selectedSat = satPassList.first().tle
            predictor = satPassList.first().predictor
            trackView = TrackView(mainActivity)
            mapFrame.addView(trackView)
            service.scheduleAtFixedRate(
                { trackView.invalidate() },
                delay,
                delay,
                TimeUnit.MILLISECONDS
            )
        } else {
            fab.setOnClickListener {
                Toast.makeText(
                    mainActivity,
                    getString(R.string.no_selected_sat),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showSelectSatDialog(list: List<SatPass>) {
        val tleArray = arrayOfNulls<String>(list.size).apply {
            list.withIndex().forEach {
                this[it.index] = it.value.tle.name
            }
        }

        val builder = AlertDialog.Builder(mainActivity)
        builder.setTitle(getString(R.string.dialog_show_track))
            .setSingleChoiceItems(tleArray, checkedItem) { dialog, which ->
                checkedItem = which
                selectedSat = list[which].tle
                predictor = list[which].predictor
                trackView.invalidate()
                dialog.dismiss()
            }
            .create()
            .show()
    }

    inner class TrackView(context: Context) : View(context) {
        private val scale = resources.displayMetrics.density
        private val groundTrackPaint = Paint().apply {
            isAntiAlias = true
            color = resources.getColor(R.color.satTrack, mainActivity.theme)
            style = Paint.Style.STROKE
            strokeWidth = scale
        }
        private val footprintPaint = Paint().apply {
            isAntiAlias = true
            color = resources.getColor(R.color.satFootprint, mainActivity.theme)
            style = Paint.Style.STROKE
            strokeWidth = scale
        }
        private val txtPaint = Paint().apply {
            isAntiAlias = true
            color = resources.getColor(R.color.satFootprint, mainActivity.theme)
            style = Paint.Style.FILL
            strokeWidth = scale
            textSize = 16f
            setShadowLayer(2f, 2f, 2f, Color.BLACK)
        }
        private val outlinePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = scale
            textSize = 16f
        }
        private val rect = Rect()

        override fun onDraw(canvas: Canvas) {
            canvas.translate(width / 2f, height / 2f)
            val degLon = width / 360f
            val degLat = height / 180f
            drawHomeLoc(canvas, degLon, degLat)
            val currentTime = getDateFor(System.currentTimeMillis())
            val orbitalPeriod = (24 * 60 / selectedSat.meanmo).toInt()
            val positions = predictor.getPositions(currentTime, 60, 0, orbitalPeriod * 3)
            drawGroundTrack(canvas, degLon, degLat, positions)
            satPassList.forEach {
                drawSat(canvas, degLon, degLat, it.tle, it.predictor, currentTime)
            }
        }

        private fun drawHomeLoc(canvas: Canvas, degLon: Float, degLat: Float) {
            val lon = gsp.longitude.toFloat()
            val lat = gsp.latitude.toFloat() * -1
            val cx = lon * degLon
            val cy = lat * degLat
            canvas.drawCircle(cx, cy, scale * 2, txtPaint)
            canvas.drawText(
                context.getString(R.string.map_gsp),
                cx - txtPaint.textSize,
                cy - txtPaint.textSize,
                txtPaint
            )
        }

        private fun drawGroundTrack(
            canvas: Canvas,
            degLon: Float,
            degLat: Float,
            list: List<SatPos>
        ) {
            val path = Path()
            var lon: Float
            var lat: Float
            var lastLon = 181f

            list.withIndex().forEach { (index, satPos) ->
                lon = rad2Deg(satPos.longitude).toFloat()
                lat = rad2Deg(satPos.latitude).toFloat() * -1

                if (lon > 180f) lon -= 360f

                lon *= degLon
                lat *= degLat

                if (index == 0 || abs(lon - lastLon) > 180) path.moveTo(lon, lat)
                else path.lineTo(lon, lat)

                lastLon = lon
            }
            canvas.drawPath(path, groundTrackPaint)
        }

        private fun drawSat(
            canvas: Canvas,
            degLon: Float,
            degLat: Float,
            tle: TLE,
            predictor: PassPredictor,
            date: Date
        ) {
            val satPosNow = predictor.getSatPos(date)
            val footprintPosList = satPosNow.rangeCircle
            drawFootprint(canvas, degLon, degLat, footprintPosList)
            drawName(canvas, degLon, degLat, satPosNow, tle.name)
        }

        private fun drawFootprint(
            canvas: Canvas,
            degLon: Float,
            degLat: Float,
            list: List<Position>
        ) {
            val path = Path()
            var lon: Float
            var lat: Float
            var lastLon = 181f

            list.withIndex().forEach { (index, position) ->
                lon = position.lon.toFloat()
                lat = position.lat.toFloat() * -1

                if (lon > 180f) lon -= 360f

                lon *= degLon
                lat *= degLat

                if (index == 0 || abs(lon - lastLon) > 180) path.moveTo(lon, lat)
                else path.lineTo(lon, lat)

                lastLon = lon
            }
            canvas.drawPath(path, footprintPaint)
        }

        private fun drawName(
            canvas: Canvas,
            degLon: Float,
            degLat: Float,
            position: SatPos,
            name: String
        ) {
            var lon = rad2Deg(position.longitude).toFloat()
            val lat = rad2Deg(position.latitude).toFloat() * -1

            if (lon > 180f) lon -= 360f

            val cx = lon * degLon
            val cy = lat * degLat

            canvas.drawCircle(cx, cy, scale * 2, txtPaint)
            txtPaint.getTextBounds(name, 0, name.length, rect)
            canvas.drawText(name, cx - rect.width() / 2, cy - txtPaint.textSize, outlinePaint)
            canvas.drawText(name, cx - rect.width() / 2, cy - txtPaint.textSize, txtPaint)
        }

        private fun getDateFor(value: Long): Date {
            return Date(value)
        }

        private fun rad2Deg(value: Double): Double {
            return value * 180 / Math.PI
        }
    }
}