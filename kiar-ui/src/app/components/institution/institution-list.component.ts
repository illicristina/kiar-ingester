import {AfterViewInit, Component, OnInit, ViewChild} from "@angular/core";
import {ConfigService, Institution, InstitutionService} from "../../../../openapi";
import {map, Observable, shareReplay, tap} from "rxjs";
import {MatPaginator} from "@angular/material/paginator";
import {InstitutionDatasource} from "./institution-datasource";
import {MatSort} from "@angular/material/sort";
import {MatSnackBar, MatSnackBarConfig} from "@angular/material/snack-bar";
import {MatDialog} from "@angular/material/dialog";
import {InstitutionDialogComponent} from "./institution-dialog.component";

@Component({
  selector: 'kiar-institution-list',
  templateUrl: './institution-list.component.html',
  styleUrls: ['./institution-list.component.scss']
})
export class InstitutionListComponent implements AfterViewInit  {

  /** {@link Observable} of all available participants. */
  public readonly dataSource: InstitutionDatasource

  /** An {@link Observable} of available participants. */
  public readonly collections: Observable<Array<string[]>>

  /** The columns that should be displayed in the data table. */
  public readonly displayedColumns: string[] = ['name', 'displayName', 'participant', 'street', 'city', 'zip', 'canton', 'email', 'action'];

  /** Reference to the {@link MatPaginator}*/
  @ViewChild(MatPaginator) paginator: MatPaginator;

  /** Reference to the {@link MatSort}*/
  @ViewChild(MatSort) sort: MatSort;

  constructor(private institution: InstitutionService, private config: ConfigService, private dialog: MatDialog, private snackBar: MatSnackBar) {
    this.dataSource = new InstitutionDatasource(this.institution)
    this.collections = this.config.getListSolrConfiguration().pipe(
        map((configs) => {
          return configs.map(config => config.collections.filter(c => c.type === "MUSEUM").flatMap(collection => [config.name, collection.name]))
        }),
        shareReplay(1, 30000)
    )
  }

  /**
   * Registers an observable for page change.
   */
  public ngAfterViewInit() {
    this.sort.direction = 'asc'
    this.dataSource.load(0, 15, this.sort.active, this.sort.direction);
    this.paginator.page.pipe(tap(() => this.dataSource.load(this.paginator.pageIndex, this.paginator.pageSize, this.sort.active, this.sort.direction))).subscribe();
    this.sort.sortChange.pipe(tap(() => this.dataSource.load(this.paginator.pageIndex, this.paginator.pageSize, this.sort.active, this.sort.direction))).subscribe();
  }

  /**
   * Opens a dialog to add a new {@link Institution} to the collection and persists it through the API upon saving.
   */
  public add() {
    this.dialog.open(InstitutionDialogComponent).afterClosed().subscribe(institution => {
      if (institution != null) {
        this.institution.postCreateInstitution(institution).subscribe({
          next: (value) => {
            this.snackBar.open(value.description, "Dismiss", { duration: 2000 } as MatSnackBarConfig);
            this.dataSource.load(this.paginator.pageIndex, this.paginator.pageSize, this.sort.active, this.sort.direction);
          },
          error: (err) => this.snackBar.open(`Error occurred while trying to create institution: ${err?.error?.description}.`, "Dismiss", { duration: 2000 } as MatSnackBarConfig),
        })
      }
    })
  }

  /**
   * Opens a dialog to edit an existing {@link Institution} to the collection and persists it through the API upon saving.
   */
  public edit(institution: Institution) {
    this.dialog.open(InstitutionDialogComponent, {data: institution}).afterClosed().subscribe(ret => {
      if (ret != null) {
        this.institution.putUpdateInstitution(ret.id!!, ret).subscribe({
          next: (value) => {
            this.snackBar.open(value.description, "Dismiss", { duration: 2000 } as MatSnackBarConfig);
            this.dataSource.load(this.paginator.pageIndex, this.paginator.pageSize, this.sort.active, this.sort.direction);
          },
          error: (err) => this.snackBar.open(`Error occurred while trying to create institution: ${err?.error?.description}.`, "Dismiss", { duration: 2000 } as MatSnackBarConfig),
        })
      }
    })
  }

  /**
   * Opens a dialog to add a new {@link Institution} to the collection and persists it through the API upon saving.
   */
  public delete(institution: Institution) {
    if (confirm(`Are you sure that you want to delete institution '${institution.id}'?\nAfter deletion, it can no longer be retrieved.`)) {
      this.institution.deleteInstitution(institution.id!!).subscribe({
        next: (value) => {
          this.snackBar.open(value.description, "Dismiss", { duration: 2000 } as MatSnackBarConfig);
          this.dataSource.load(this.paginator.pageIndex, this.paginator.pageSize, this.sort.active, this.sort.direction);
        },
        error: (err) => this.snackBar.open(`Error occurred while trying to delete institution '${institution.name}': ${err?.error?.description}.`, "Dismiss", { duration: 2000 } as MatSnackBarConfig),
      })
    }
  }

  /**
   * Uses the API to trigger synchronisation of institution master data with the Apache Solt backend.
   *
   * @param config The name of the Apache Solr configuration to use.
   * @param collection The name of the collection to use.
   */
  public synchronize(config: string, collection: string) {
    this.institution.postSynchronizeInstitutions(config, collection).subscribe({
      next: (value) =>  this.snackBar.open(`Successfully synchronised institutions with Apache Solr backend (${collection} (${config}).`, "Dismiss", { duration: 2000 } as MatSnackBarConfig),
      error: (err) => this.snackBar.open(`Error occurred while synchronising institutions with Apache Solr backend (${collection} (${config}): ${err?.error?.description}.`, "Dismiss", { duration: 2000 } as MatSnackBarConfig),
    })
  }
}